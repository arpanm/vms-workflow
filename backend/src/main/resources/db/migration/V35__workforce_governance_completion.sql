CREATE TABLE employee_aliases (
    id UUID PRIMARY KEY,
    employee_id UUID NOT NULL REFERENCES employees(id),
    alias_type VARCHAR(32) NOT NULL
        CHECK (alias_type IN ('HRIS_ID', 'EMAIL', 'BADGE', 'LEGACY_ID', 'OTHER')),
    alias_value VARCHAR(320) NOT NULL CHECK (btrim(alias_value) <> ''),
    valid_from DATE NOT NULL,
    valid_to DATE,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'ENDED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_subject VARCHAR(255) NOT NULL,
    CONSTRAINT ck_employee_alias_dates
        CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CONSTRAINT uq_employee_alias_value
        UNIQUE (employee_id, alias_type, alias_value, valid_from),
    CONSTRAINT ex_employee_alias_effective
        EXCLUDE USING gist (
            employee_id WITH =,
            alias_type WITH =,
            alias_value WITH =,
            daterange(valid_from, COALESCE(valid_to + 1, 'infinity'::date), '[)') WITH &&
        )
);

CREATE TABLE employee_deliverable_allocations (
    id UUID PRIMARY KEY,
    employee_id UUID NOT NULL REFERENCES employees(id),
    project_allocation_id UUID NOT NULL REFERENCES employee_project_allocations(id),
    deliverable_id UUID NOT NULL REFERENCES delivery_deliverables(id),
    valid_from DATE NOT NULL,
    valid_to DATE,
    allocation_percent NUMERIC(5,2) NOT NULL
        CHECK (allocation_percent > 0 AND allocation_percent <= 100),
    role_on_deliverable VARCHAR(128),
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('PLANNED', 'ACTIVE', 'ENDED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_subject VARCHAR(255) NOT NULL,
    CONSTRAINT ck_deliverable_allocation_dates
        CHECK (valid_to IS NULL OR valid_to >= valid_from)
);

CREATE OR REPLACE FUNCTION enforce_deliverable_allocation_integrity()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
DECLARE
    project_employee UUID;
    project_from DATE;
    project_to DATE;
    project_percent NUMERIC(5,2);
    deliverable_plan UUID;
    allocation_plan UUID;
BEGIN
    SELECT employee_id, valid_from, valid_to, allocation_percent
      INTO project_employee, project_from, project_to, project_percent
    FROM public.employee_project_allocations
    WHERE id = NEW.project_allocation_id;
    IF project_employee IS NULL
       OR project_employee <> NEW.employee_id
       OR NEW.valid_from < project_from
       OR (project_to IS NOT NULL
           AND (NEW.valid_to IS NULL OR NEW.valid_to > project_to)) THEN
        RAISE EXCEPTION
            'Deliverable allocation must remain inside the employee project allocation';
    END IF;

    SELECT plan_id INTO deliverable_plan
    FROM public.delivery_deliverables WHERE id = NEW.deliverable_id;
    SELECT plan.id INTO allocation_plan
    FROM public.delivery_plans plan
    JOIN public.engagement_months month ON month.id = plan.engagement_month_id
    JOIN public.employee_project_allocations allocation
      ON allocation.id = NEW.project_allocation_id
     AND allocation.engagement_id = month.engagement_id
    WHERE plan.id = deliverable_plan;
    IF allocation_plan IS NULL THEN
        RAISE EXCEPTION
            'Deliverable and project allocation engagement scope mismatch';
    END IF;

    IF EXISTS (
        WITH candidates AS (
            SELECT id, valid_from, valid_to, allocation_percent
            FROM public.employee_deliverable_allocations
            WHERE employee_id = NEW.employee_id
              AND project_allocation_id = NEW.project_allocation_id
              AND status IN ('PLANNED', 'ACTIVE')
              AND id <> NEW.id
              AND daterange(
                    valid_from,
                    COALESCE(valid_to + 1, 'infinity'::date),
                    '[)'
                  ) && daterange(
                    NEW.valid_from,
                    COALESCE(NEW.valid_to + 1, 'infinity'::date),
                    '[)'
                  )
            UNION ALL
            SELECT NEW.id, NEW.valid_from, NEW.valid_to, NEW.allocation_percent
            WHERE NEW.status IN ('PLANNED', 'ACTIVE')
        ),
        boundaries AS (
            SELECT valid_from AS boundary_date FROM candidates
            UNION
            SELECT valid_to + 1 FROM candidates WHERE valid_to IS NOT NULL
        )
        SELECT 1
        FROM boundaries boundary
        WHERE (
            SELECT COALESCE(SUM(candidate.allocation_percent), 0)
            FROM candidates candidate
            WHERE daterange(
                    candidate.valid_from,
                    COALESCE(candidate.valid_to + 1, 'infinity'::date),
                    '[)'
                  ) @> boundary.boundary_date
        ) > project_percent
    ) THEN
        RAISE EXCEPTION
            'Deliverable allocations exceed the effective project allocation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER employee_deliverable_allocation_integrity
BEFORE INSERT OR UPDATE ON employee_deliverable_allocations
FOR EACH ROW EXECUTE FUNCTION enforce_deliverable_allocation_integrity();

CREATE TABLE leave_policy_versions (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    leave_type_id UUID NOT NULL REFERENCES leave_types(id),
    version INTEGER NOT NULL CHECK (version > 0),
    status VARCHAR(16) NOT NULL
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'SUPERSEDED')),
    valid_from DATE NOT NULL,
    valid_to DATE,
    approval_required BOOLEAN NOT NULL DEFAULT TRUE,
    maximum_units_per_request NUMERIC(8,2)
        CHECK (maximum_units_per_request IS NULL
            OR maximum_units_per_request > 0),
    excess_to_lwp BOOLEAN NOT NULL DEFAULT TRUE,
    cancellation_allowed BOOLEAN NOT NULL DEFAULT TRUE,
    rules JSONB NOT NULL DEFAULT '{}'::jsonb,
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_subject VARCHAR(255) NOT NULL,
    CONSTRAINT uq_leave_policy_version
        UNIQUE (organization_id, leave_type_id, version),
    CONSTRAINT ck_leave_policy_dates
        CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CONSTRAINT ck_leave_policy_publication
        CHECK (
            (status = 'DRAFT' AND published_at IS NULL)
            OR (status <> 'DRAFT' AND published_at IS NOT NULL)
        )
);

CREATE TABLE leave_balance_commands (
    id UUID PRIMARY KEY,
    employee_id UUID NOT NULL REFERENCES employees(id),
    leave_type_id UUID NOT NULL REFERENCES leave_types(id),
    command_type VARCHAR(24) NOT NULL
        CHECK (command_type IN ('ACCRUAL', 'GRANT', 'ADJUSTMENT')),
    quantity NUMERIC(8,2) NOT NULL CHECK (quantity <> 0),
    effective_date DATE NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL CHECK (btrim(idempotency_key) <> ''),
    reason TEXT NOT NULL CHECK (btrim(reason) <> ''),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_subject VARCHAR(255) NOT NULL,
    UNIQUE (employee_id, idempotency_key)
);

CREATE TABLE leave_request_decisions (
    id UUID PRIMARY KEY,
    leave_request_id UUID NOT NULL REFERENCES leave_requests(id),
    decision VARCHAR(16) NOT NULL
        CHECK (decision IN ('APPROVE', 'REJECT', 'CANCEL')),
    expected_request_status VARCHAR(24) NOT NULL,
    expected_request_version BIGINT NOT NULL CHECK (expected_request_version >= 0),
    resulting_request_status VARCHAR(24) NOT NULL,
    resulting_request_version BIGINT NOT NULL CHECK (resulting_request_version > 0),
    reason TEXT NOT NULL CHECK (btrim(reason) <> ''),
    paid_units NUMERIC(8,2) NOT NULL DEFAULT 0 CHECK (paid_units >= 0),
    lwp_units NUMERIC(8,2) NOT NULL DEFAULT 0 CHECK (lwp_units >= 0),
    idempotency_key VARCHAR(160) NOT NULL CHECK (btrim(idempotency_key) <> ''),
    decided_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_by_subject VARCHAR(255) NOT NULL,
    UNIQUE (leave_request_id, idempotency_key)
);

ALTER TABLE leave_requests
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0);

CREATE TABLE attendance_breaks (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES attendance_sessions(id),
    employee_id UUID NOT NULL REFERENCES employees(id),
    break_start_event_id UUID NOT NULL UNIQUE REFERENCES attendance_events(id),
    break_end_event_id UUID UNIQUE REFERENCES attendance_events(id),
    started_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    minutes INTEGER,
    status VARCHAR(16) NOT NULL
        CHECK (status IN ('OPEN', 'CLOSED')),
    CONSTRAINT ck_attendance_break_close CHECK (
        (status = 'OPEN' AND break_end_event_id IS NULL
            AND ended_at IS NULL AND minutes IS NULL)
        OR (status = 'CLOSED' AND break_end_event_id IS NOT NULL
            AND ended_at > started_at AND minutes >= 0)
    )
);

CREATE UNIQUE INDEX uq_employee_open_break
    ON attendance_breaks(employee_id) WHERE status = 'OPEN';

CREATE TABLE workforce_import_batches (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    import_type VARCHAR(40) NOT NULL
        CHECK (import_type IN (
            'EMPLOYEE_ALIASES',
            'DELIVERABLE_ALLOCATIONS',
            'LEAVE_BALANCE_COMMANDS'
        )),
    original_file_name VARCHAR(255) NOT NULL,
    content_checksum VARCHAR(64) NOT NULL
        CHECK (content_checksum ~ '^[0-9a-f]{64}$'),
    idempotency_key VARCHAR(160) NOT NULL CHECK (btrim(idempotency_key) <> ''),
    status VARCHAR(24) NOT NULL
        CHECK (status IN ('VALIDATED', 'IMPORTED', 'FAILED')),
    row_count INTEGER NOT NULL CHECK (row_count >= 0),
    error_count INTEGER NOT NULL CHECK (error_count >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_subject VARCHAR(255) NOT NULL,
    UNIQUE (organization_id, idempotency_key)
);

CREATE TABLE workforce_import_errors (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL REFERENCES workforce_import_batches(id),
    row_number INTEGER NOT NULL CHECK (row_number > 1),
    field_name VARCHAR(80) NOT NULL,
    error_code VARCHAR(80) NOT NULL,
    message TEXT NOT NULL
);

CREATE TRIGGER employee_aliases_immutable
BEFORE UPDATE OR DELETE ON employee_aliases
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TRIGGER leave_policy_versions_immutable
BEFORE UPDATE OR DELETE ON leave_policy_versions
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TRIGGER leave_balance_commands_immutable
BEFORE UPDATE OR DELETE ON leave_balance_commands
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TRIGGER leave_request_decisions_immutable
BEFORE UPDATE OR DELETE ON leave_request_decisions
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TRIGGER workforce_import_batches_immutable
BEFORE UPDATE OR DELETE ON workforce_import_batches
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TRIGGER workforce_import_errors_immutable
BEFORE UPDATE OR DELETE ON workforce_import_errors
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

ALTER TABLE employee_aliases OWNER TO vms_migration_owner;
ALTER TABLE employee_deliverable_allocations OWNER TO vms_migration_owner;
ALTER TABLE leave_policy_versions OWNER TO vms_migration_owner;
ALTER TABLE leave_balance_commands OWNER TO vms_migration_owner;
ALTER TABLE leave_request_decisions OWNER TO vms_migration_owner;
ALTER TABLE attendance_breaks OWNER TO vms_migration_owner;
ALTER TABLE workforce_import_batches OWNER TO vms_migration_owner;
ALTER TABLE workforce_import_errors OWNER TO vms_migration_owner;

ALTER FUNCTION enforce_deliverable_allocation_integrity()
    OWNER TO vms_migration_owner;
REVOKE ALL ON FUNCTION enforce_deliverable_allocation_integrity() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION enforce_deliverable_allocation_integrity()
TO vms_app_runtime, vms_migration_owner;

REVOKE ALL ON TABLE
    employee_aliases,
    employee_deliverable_allocations,
    leave_policy_versions,
    leave_balance_commands,
    leave_request_decisions,
    attendance_breaks,
    workforce_import_batches,
    workforce_import_errors
FROM PUBLIC, vms_reporting, vms_job_worker, vms_migration_processor;

GRANT SELECT, INSERT ON TABLE
    employee_aliases,
    employee_deliverable_allocations,
    leave_policy_versions,
    leave_balance_commands,
    leave_request_decisions,
    attendance_breaks,
    workforce_import_batches,
    workforce_import_errors
TO vms_app_runtime;

GRANT UPDATE (status, version) ON leave_requests TO vms_app_runtime;
GRANT UPDATE (check_out_event_id, check_out_at, net_minutes, status)
    ON attendance_sessions TO vms_app_runtime;
GRANT UPDATE (break_end_event_id, ended_at, minutes, status)
    ON attendance_breaks TO vms_app_runtime;

GRANT SELECT ON TABLE
    employee_aliases,
    employee_deliverable_allocations,
    leave_policy_versions,
    leave_balance_commands,
    leave_request_decisions,
    attendance_breaks,
    workforce_import_batches,
    workforce_import_errors
TO vms_reporting, vms_backup;
