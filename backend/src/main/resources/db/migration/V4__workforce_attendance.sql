CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE employees (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    employee_number VARCHAR(64) NOT NULL,
    work_email VARCHAR(320) NOT NULL,
    user_profile_id UUID REFERENCES user_profiles(id),
    join_date DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_subject VARCHAR(255) NOT NULL,
    CONSTRAINT uq_employee_number UNIQUE (organization_id, employee_number),
    CONSTRAINT uq_employee_work_email UNIQUE (organization_id, work_email),
    CONSTRAINT uq_employee_user_link UNIQUE (organization_id, user_profile_id)
);

CREATE TABLE employee_versions (
    id UUID PRIMARY KEY,
    employee_id UUID NOT NULL REFERENCES employees(id),
    version INTEGER NOT NULL CHECK (version > 0),
    valid_from DATE NOT NULL,
    valid_to DATE,
    first_name VARCHAR(128) NOT NULL,
    last_name VARCHAR(128) NOT NULL,
    display_name VARCHAR(256) NOT NULL,
    designation VARCHAR(128),
    employment_status VARCHAR(24) NOT NULL
        CHECK (employment_status IN ('PREBOARDING', 'ACTIVE', 'ON_LEAVE', 'SUSPENDED', 'EXITED', 'ARCHIVED')),
    activation_status VARCHAR(16) NOT NULL
        CHECK (activation_status IN ('ENABLED', 'DISABLED')),
    exit_date DATE,
    reason TEXT,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    recorded_by_subject VARCHAR(255) NOT NULL,
    CONSTRAINT uq_employee_version UNIQUE (employee_id, version),
    CONSTRAINT ck_employee_version_dates CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CONSTRAINT ck_employee_exit_status
        CHECK (employment_status <> 'EXITED' OR exit_date IS NOT NULL),
    CONSTRAINT ex_employee_version_dates EXCLUDE USING gist (
        employee_id WITH =,
        daterange(valid_from, COALESCE(valid_to + 1, 'infinity'::date), '[)') WITH &&
    )
);

CREATE TABLE integration_capability_certifications (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    provider VARCHAR(32) NOT NULL CHECK (provider = 'GREYTHR'),
    status VARCHAR(24) NOT NULL
        CHECK (status IN ('DRAFT', 'CERTIFIED', 'REVOKED')),
    certified_at TIMESTAMPTZ,
    capability_manifest JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_certified_capability_time
        CHECK (status <> 'CERTIFIED' OR certified_at IS NOT NULL)
);

CREATE TABLE attendance_source_mode_assignments (
    id UUID PRIMARY KEY,
    employee_id UUID NOT NULL REFERENCES employees(id),
    mode VARCHAR(32) NOT NULL
        CHECK (mode IN ('INTERNAL_AUTHORITATIVE', 'GREYTHR_AUTHORITATIVE', 'HYBRID_TRANSITION', 'HISTORICAL_IMPORT')),
    authoritative_source VARCHAR(24) NOT NULL
        CHECK (authoritative_source IN ('INTERNAL', 'GREYTHR', 'IMPORT')),
    capability_certification_id UUID REFERENCES integration_capability_certifications(id),
    valid_from DATE NOT NULL,
    valid_to DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_subject VARCHAR(255) NOT NULL,
    CONSTRAINT ck_source_mode_dates CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CONSTRAINT ck_greythr_capability_gate CHECK (
        mode <> 'GREYTHR_AUTHORITATIVE' OR capability_certification_id IS NOT NULL
    ),
    CONSTRAINT ex_employee_source_mode_dates EXCLUDE USING gist (
        employee_id WITH =,
        daterange(valid_from, COALESCE(valid_to + 1, 'infinity'::date), '[)') WITH &&
    )
);

CREATE TABLE employee_project_allocations (
    id UUID PRIMARY KEY,
    employee_id UUID NOT NULL REFERENCES employees(id),
    engagement_id UUID NOT NULL REFERENCES engagements(id),
    project_id UUID NOT NULL,
    valid_from DATE NOT NULL,
    valid_to DATE,
    allocation_percent NUMERIC(5,2) NOT NULL
        CHECK (allocation_percent > 0 AND allocation_percent <= 100),
    role_on_project VARCHAR(128),
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('PLANNED', 'ACTIVE', 'TEMPORARILY_INACTIVE', 'ENDED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_subject VARCHAR(255) NOT NULL,
    CONSTRAINT ck_allocation_dates CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CONSTRAINT fk_allocation_project_engagement
        FOREIGN KEY (project_id, engagement_id) REFERENCES projects(id, engagement_id)
);

CREATE OR REPLACE FUNCTION enforce_employee_allocation_integrity()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM employees emp
        JOIN engagements e ON e.id = NEW.engagement_id
        WHERE emp.id = NEW.employee_id
          AND (emp.organization_id = e.client_organization_id
            OR emp.organization_id = e.vendor_organization_id
            OR emp.organization_id = e.procurement_organization_id)
    ) THEN
        RAISE EXCEPTION 'Employee organization does not participate in allocation engagement'
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        WITH candidate_allocations AS (
            SELECT id, valid_from, valid_to, allocation_percent
            FROM employee_project_allocations
            WHERE employee_id = NEW.employee_id
              AND status IN ('PLANNED', 'ACTIVE')
              AND id <> NEW.id
              AND daterange(valid_from, COALESCE(valid_to + 1, 'infinity'::date), '[)')
                  && daterange(NEW.valid_from, COALESCE(NEW.valid_to + 1, 'infinity'::date), '[)')
            UNION ALL
            SELECT NEW.id, NEW.valid_from, NEW.valid_to, NEW.allocation_percent
            WHERE NEW.status IN ('PLANNED', 'ACTIVE')
        ),
        boundaries AS (
            SELECT valid_from AS boundary_date FROM candidate_allocations
            UNION
            SELECT valid_to + 1 FROM candidate_allocations WHERE valid_to IS NOT NULL
        )
        SELECT 1
        FROM boundaries b
        WHERE (
            SELECT COALESCE(SUM(c.allocation_percent), 0)
            FROM candidate_allocations c
            WHERE daterange(c.valid_from, COALESCE(c.valid_to + 1, 'infinity'::date), '[)')
                @> b.boundary_date
        ) > 100
    ) THEN
        RAISE EXCEPTION 'Concurrent employee allocations exceed 100 percent'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER employee_allocation_integrity
BEFORE INSERT OR UPDATE OF employee_id, engagement_id, project_id, valid_from, valid_to,
    allocation_percent, status
ON employee_project_allocations
FOR EACH ROW
EXECUTE FUNCTION enforce_employee_allocation_integrity();

CREATE TABLE working_calendar_versions (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    name VARCHAR(128) NOT NULL,
    timezone VARCHAR(64) NOT NULL,
    version INTEGER NOT NULL CHECK (version > 0),
    valid_from DATE NOT NULL,
    valid_to DATE,
    expected_full_minutes INTEGER NOT NULL DEFAULT 540 CHECK (expected_full_minutes > 0),
    expected_half_minutes INTEGER NOT NULL DEFAULT 270 CHECK (expected_half_minutes > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_calendar_version UNIQUE (organization_id, name, version),
    CONSTRAINT ck_calendar_dates CHECK (valid_to IS NULL OR valid_to >= valid_from)
);

CREATE TABLE working_calendar_weekdays (
    calendar_version_id UUID NOT NULL REFERENCES working_calendar_versions(id),
    iso_weekday SMALLINT NOT NULL CHECK (iso_weekday BETWEEN 1 AND 7),
    classification VARCHAR(24) NOT NULL
        CHECK (classification IN ('WORKING', 'WEEKLY_OFF', 'HALF_DAY_EXPECTED')),
    expected_minutes INTEGER NOT NULL CHECK (expected_minutes >= 0),
    PRIMARY KEY (calendar_version_id, iso_weekday)
);

CREATE TABLE calendar_holidays (
    id UUID PRIMARY KEY,
    calendar_version_id UUID NOT NULL REFERENCES working_calendar_versions(id),
    holiday_date DATE NOT NULL,
    name VARCHAR(128) NOT NULL,
    classification VARCHAR(24) NOT NULL
        CHECK (classification IN ('HOLIDAY', 'HALF_DAY_EXPECTED')),
    expected_minutes INTEGER NOT NULL DEFAULT 0 CHECK (expected_minutes >= 0),
    UNIQUE (calendar_version_id, holiday_date)
);

CREATE TABLE employee_calendar_assignments (
    id UUID PRIMARY KEY,
    employee_id UUID NOT NULL REFERENCES employees(id),
    calendar_version_id UUID NOT NULL REFERENCES working_calendar_versions(id),
    valid_from DATE NOT NULL,
    valid_to DATE,
    CONSTRAINT ck_employee_calendar_dates CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CONSTRAINT ex_employee_calendar_dates EXCLUDE USING gist (
        employee_id WITH =,
        daterange(valid_from, COALESCE(valid_to + 1, 'infinity'::date), '[)') WITH &&
    )
);

CREATE TABLE employee_date_overrides (
    id UUID PRIMARY KEY,
    employee_id UUID NOT NULL REFERENCES employees(id),
    override_date DATE NOT NULL,
    classification VARCHAR(24) NOT NULL
        CHECK (classification IN ('WORKING', 'NON_WORKING', 'HALF_DAY_EXPECTED')),
    expected_minutes INTEGER NOT NULL CHECK (expected_minutes >= 0),
    reason TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_subject VARCHAR(255) NOT NULL,
    UNIQUE (employee_id, override_date)
);

CREATE TABLE leave_types (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    code VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    paid BOOLEAN NOT NULL,
    balance_tracked BOOLEAN NOT NULL,
    minimum_increment NUMERIC(4,2) NOT NULL DEFAULT 0.5
        CHECK (minimum_increment > 0 AND minimum_increment <= 1),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'INACTIVE')),
    UNIQUE (organization_id, code)
);

CREATE TABLE leave_balance_ledger (
    id UUID PRIMARY KEY,
    employee_id UUID NOT NULL REFERENCES employees(id),
    leave_type_id UUID NOT NULL REFERENCES leave_types(id),
    entry_type VARCHAR(40) NOT NULL CHECK (entry_type IN (
        'OPENING_BALANCE', 'MONTHLY_ACCRUAL', 'ANNUAL_GRANT',
        'MANUAL_ADJUSTMENT_CREDIT', 'MANUAL_ADJUSTMENT_DEBIT',
        'LEAVE_RESERVED', 'LEAVE_CONSUMED', 'LEAVE_RELEASED',
        'EXPIRY', 'CARRY_FORWARD', 'MIGRATION_CORRECTION'
    )),
    quantity NUMERIC(8,2) NOT NULL CHECK (quantity <> 0),
    effective_date DATE NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    reference_type VARCHAR(40),
    reference_id UUID,
    reason TEXT,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    recorded_by_subject VARCHAR(255) NOT NULL,
    UNIQUE (employee_id, leave_type_id, idempotency_key)
);

CREATE TABLE leave_requests (
    id UUID PRIMARY KEY,
    employee_id UUID NOT NULL REFERENCES employees(id),
    leave_type_id UUID NOT NULL REFERENCES leave_types(id),
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    requested_units NUMERIC(8,2) NOT NULL CHECK (requested_units > 0),
    paid_units NUMERIC(8,2) NOT NULL CHECK (paid_units >= 0),
    lwp_units NUMERIC(8,2) NOT NULL CHECK (lwp_units >= 0),
    reason TEXT NOT NULL,
    status VARCHAR(24) NOT NULL
        CHECK (status IN ('SUBMITTED', 'APPROVED', 'REJECTED', 'CANCELLED')),
    idempotency_key VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_subject VARCHAR(255) NOT NULL,
    CONSTRAINT ck_leave_request_dates CHECK (end_date >= start_date),
    CONSTRAINT ck_leave_request_split CHECK (paid_units + lwp_units = requested_units),
    UNIQUE (employee_id, idempotency_key)
);

CREATE TABLE attendance_events (
    id UUID PRIMARY KEY,
    employee_id UUID NOT NULL REFERENCES employees(id),
    event_type VARCHAR(24) NOT NULL
        CHECK (event_type IN ('CHECK_IN', 'CHECK_OUT', 'BREAK_START', 'BREAK_END', 'ADMIN_PUNCH', 'IMPORTED_PUNCH')),
    occurred_at TIMESTAMPTZ NOT NULL,
    work_date DATE NOT NULL,
    source VARCHAR(24) NOT NULL
        CHECK (source IN ('INTERNAL_WEB', 'GREYTHR', 'CSV_IMPORT', 'ADMIN_CORRECTION')),
    idempotency_key VARCHAR(160) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    recorded_by_subject VARCHAR(255) NOT NULL,
    UNIQUE (employee_id, idempotency_key)
);

CREATE TABLE attendance_sessions (
    id UUID PRIMARY KEY,
    employee_id UUID NOT NULL REFERENCES employees(id),
    work_date DATE NOT NULL,
    check_in_event_id UUID NOT NULL UNIQUE REFERENCES attendance_events(id),
    check_out_event_id UUID UNIQUE REFERENCES attendance_events(id),
    check_in_at TIMESTAMPTZ NOT NULL,
    check_out_at TIMESTAMPTZ,
    net_minutes INTEGER,
    status VARCHAR(16) NOT NULL CHECK (status IN ('OPEN', 'CLOSED', 'INVALID', 'SUPERSEDED')),
    CONSTRAINT ck_session_close CHECK (
        (status = 'OPEN' AND check_out_event_id IS NULL AND check_out_at IS NULL AND net_minutes IS NULL)
        OR (status <> 'OPEN' AND check_out_event_id IS NOT NULL AND check_out_at > check_in_at AND net_minutes >= 0)
    ),
    CONSTRAINT ex_attendance_session_overlap EXCLUDE USING gist (
        employee_id WITH =,
        tstzrange(check_in_at, COALESCE(check_out_at, 'infinity'::timestamptz), '[)') WITH &&
    ) WHERE (status IN ('OPEN', 'CLOSED'))
);

CREATE UNIQUE INDEX uq_employee_open_session
    ON attendance_sessions(employee_id) WHERE status = 'OPEN';

CREATE TABLE attendance_days (
    id UUID PRIMARY KEY,
    employee_id UUID NOT NULL REFERENCES employees(id),
    work_date DATE NOT NULL,
    calculation_version INTEGER NOT NULL CHECK (calculation_version > 0),
    is_current BOOLEAN NOT NULL DEFAULT TRUE,
    expected_classification VARCHAR(24) NOT NULL,
    expected_minutes INTEGER NOT NULL CHECK (expected_minutes >= 0),
    source_mode VARCHAR(32) NOT NULL,
    net_minutes INTEGER NOT NULL CHECK (net_minutes >= 0),
    leave_units NUMERIC(4,2) NOT NULL DEFAULT 0 CHECK (leave_units >= 0),
    leave_type_code VARCHAR(32),
    final_status VARCHAR(64) NOT NULL,
    exception_code VARCHAR(64),
    computed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (employee_id, work_date, calculation_version)
);

CREATE UNIQUE INDEX uq_current_attendance_day
    ON attendance_days(employee_id, work_date) WHERE is_current;

CREATE TABLE attendance_exceptions (
    id UUID PRIMARY KEY,
    employee_id UUID NOT NULL REFERENCES employees(id),
    work_date DATE NOT NULL,
    exception_code VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN', 'RESOLVED')),
    opened_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMPTZ,
    UNIQUE (employee_id, work_date, exception_code)
);

CREATE TABLE attendance_regularizations (
    id UUID PRIMARY KEY,
    employee_id UUID NOT NULL REFERENCES employees(id),
    work_date DATE NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    narrative TEXT NOT NULL,
    requested_outcome VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'SUBMITTED'
        CHECK (status IN ('SUBMITTED', 'UNDER_REVIEW', 'APPROVED', 'REJECTED', 'CANCELLED')),
    idempotency_key VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_subject VARCHAR(255) NOT NULL,
    UNIQUE (employee_id, idempotency_key)
);

CREATE TABLE attendance_snapshot_versions (
    id UUID PRIMARY KEY,
    engagement_month_id UUID NOT NULL REFERENCES engagement_months(id),
    version INTEGER NOT NULL CHECK (version > 0),
    supersedes_id UUID REFERENCES attendance_snapshot_versions(id),
    status VARCHAR(16) NOT NULL DEFAULT 'CLOSED' CHECK (status = 'CLOSED'),
    checksum VARCHAR(64) NOT NULL,
    day_count INTEGER NOT NULL CHECK (day_count >= 0),
    closed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    closed_by_subject VARCHAR(255) NOT NULL,
    reopen_reason TEXT,
    UNIQUE (engagement_month_id, version),
    UNIQUE (supersedes_id)
);

CREATE TABLE attendance_snapshot_days (
    snapshot_id UUID NOT NULL REFERENCES attendance_snapshot_versions(id),
    attendance_day_id UUID NOT NULL REFERENCES attendance_days(id),
    employee_id UUID NOT NULL REFERENCES employees(id),
    work_date DATE NOT NULL,
    final_status VARCHAR(64) NOT NULL,
    net_minutes INTEGER NOT NULL,
    source_mode VARCHAR(32) NOT NULL,
    PRIMARY KEY (snapshot_id, attendance_day_id)
);

CREATE OR REPLACE FUNCTION reject_immutable_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER leave_balance_ledger_immutable
BEFORE UPDATE OR DELETE ON leave_balance_ledger
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

CREATE TRIGGER attendance_events_immutable
BEFORE UPDATE OR DELETE ON attendance_events
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

CREATE TRIGGER attendance_snapshots_immutable
BEFORE UPDATE OR DELETE ON attendance_snapshot_versions
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

CREATE TRIGGER attendance_snapshot_days_immutable
BEFORE UPDATE OR DELETE ON attendance_snapshot_days
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

INSERT INTO permissions (id, code, description) VALUES
    ('10000000-0000-0000-0000-000000000010', 'workforce.read', 'Read workforce records within an authorized scope'),
    ('10000000-0000-0000-0000-000000000011', 'workforce.manage', 'Manage workforce records within an authorized scope'),
    ('10000000-0000-0000-0000-000000000012', 'attendance.self', 'Read and capture the linked employee attendance'),
    ('10000000-0000-0000-0000-000000000013', 'attendance.review', 'Review attendance within an authorized scope'),
    ('10000000-0000-0000-0000-000000000014', 'attendance.close', 'Close an authorized engagement attendance month'),
    ('10000000-0000-0000-0000-000000000015', 'attendance.reopen', 'Reopen an authorized engagement attendance snapshot');

INSERT INTO roles (id, code, name, description) VALUES
    ('11000000-0000-0000-0000-000000000010', 'VENDOR_HR_ADMIN', 'Vendor HR administrator', 'Workforce and attendance administration'),
    ('11000000-0000-0000-0000-000000000011', 'EMPLOYEE', 'Employee', 'Linked employee self-service attendance');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON (
    (r.code = 'ORG_ADMIN' AND p.code IN (
        'workforce.read', 'workforce.manage', 'attendance.review', 'attendance.close', 'attendance.reopen'
    ))
    OR (r.code = 'VENDOR_HR_ADMIN' AND p.code IN (
        'catalog.read', 'workforce.read', 'workforce.manage', 'attendance.review', 'attendance.close'
    ))
    OR (r.code = 'VENDOR_MANAGER' AND p.code IN (
        'workforce.read', 'attendance.review', 'attendance.close'
    ))
    OR (r.code = 'CLIENT_PRODUCT_OWNER' AND p.code IN (
        'workforce.read', 'attendance.review', 'attendance.close', 'attendance.reopen'
    ))
    OR (r.code = 'EMPLOYEE' AND p.code IN ('catalog.read', 'attendance.self'))
)
ON CONFLICT DO NOTHING;
