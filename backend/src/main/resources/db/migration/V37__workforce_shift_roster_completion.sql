CREATE TABLE workforce_shift_policy_versions (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    code VARCHAR(64) NOT NULL CHECK (btrim(code) <> ''),
    name VARCHAR(128) NOT NULL CHECK (btrim(name) <> ''),
    timezone VARCHAR(64) NOT NULL CHECK (btrim(timezone) <> ''),
    version INTEGER NOT NULL CHECK (version > 0),
    valid_from DATE NOT NULL,
    valid_to DATE,
    scheduled_start_local_time TIME NOT NULL,
    scheduled_end_local_time TIME NOT NULL,
    overnight_cutoff_local_time TIME NOT NULL,
    expected_net_minutes INTEGER NOT NULL CHECK (expected_net_minutes > 0),
    maximum_session_minutes INTEGER NOT NULL
        CHECK (maximum_session_minutes BETWEEN 1 AND 2160),
    allow_split_sessions BOOLEAN NOT NULL DEFAULT TRUE,
    minimum_break_minutes INTEGER NOT NULL DEFAULT 0
        CHECK (minimum_break_minutes >= 0),
    status VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED'
        CHECK (status IN ('PUBLISHED', 'SUPERSEDED')),
    published_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_subject VARCHAR(255) NOT NULL,
    CONSTRAINT uq_shift_policy_version
        UNIQUE (organization_id, code, version),
    CONSTRAINT ck_shift_policy_dates
        CHECK (valid_to IS NULL OR valid_to >= valid_from)
);

CREATE TABLE employee_shift_assignments (
    id UUID PRIMARY KEY,
    employee_id UUID NOT NULL REFERENCES employees(id),
    shift_policy_version_id UUID NOT NULL
        REFERENCES workforce_shift_policy_versions(id),
    valid_from DATE NOT NULL,
    valid_to DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_subject VARCHAR(255) NOT NULL,
    CONSTRAINT ck_employee_shift_dates
        CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CONSTRAINT ex_employee_shift_assignment_dates EXCLUDE USING gist (
        employee_id WITH =,
        daterange(
            valid_from,
            COALESCE(valid_to + 1, 'infinity'::date),
            '[)'
        ) WITH &&
    )
);

CREATE OR REPLACE FUNCTION enforce_employee_shift_tenant()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM public.employees employee
        JOIN public.workforce_shift_policy_versions policy
          ON policy.id = NEW.shift_policy_version_id
         AND policy.organization_id = employee.organization_id
        WHERE employee.id = NEW.employee_id
          AND NEW.valid_from >= policy.valid_from
          AND (
            policy.valid_to IS NULL
            OR (
                NEW.valid_to IS NOT NULL
                AND NEW.valid_to <= policy.valid_to
            )
          )
    ) THEN
        RAISE EXCEPTION
            'Shift assignment must use a same-organization policy and remain inside its effective range'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER employee_shift_assignment_tenant_gate
BEFORE INSERT OR UPDATE ON employee_shift_assignments
FOR EACH ROW EXECUTE FUNCTION enforce_employee_shift_tenant();

CREATE TABLE workforce_roster_snapshot_versions (
    id UUID PRIMARY KEY,
    engagement_month_id UUID NOT NULL REFERENCES engagement_months(id),
    version INTEGER NOT NULL CHECK (version > 0),
    supersedes_id UUID REFERENCES workforce_roster_snapshot_versions(id),
    status VARCHAR(16) NOT NULL DEFAULT 'FINALIZED'
        CHECK (status = 'FINALIZED'),
    checksum VARCHAR(64) NOT NULL
        CHECK (checksum ~ '^[0-9a-f]{64}$'),
    employee_count INTEGER NOT NULL CHECK (employee_count >= 0),
    employee_day_count INTEGER NOT NULL CHECK (employee_day_count >= 0),
    finalized_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finalized_by_subject VARCHAR(255) NOT NULL,
    reason TEXT NOT NULL CHECK (btrim(reason) <> ''),
    UNIQUE (engagement_month_id, version),
    UNIQUE (supersedes_id)
);

CREATE TABLE workforce_roster_snapshot_days (
    snapshot_id UUID NOT NULL REFERENCES workforce_roster_snapshot_versions(id),
    employee_id UUID NOT NULL REFERENCES employees(id),
    work_date DATE NOT NULL,
    project_allocation_id UUID NOT NULL
        REFERENCES employee_project_allocations(id),
    project_id UUID NOT NULL,
    allocation_percent NUMERIC(5,2) NOT NULL
        CHECK (allocation_percent > 0 AND allocation_percent <= 100),
    shift_policy_version_id UUID
        REFERENCES workforce_shift_policy_versions(id),
    shift_policy_code VARCHAR(64),
    shift_policy_version INTEGER,
    timezone VARCHAR(64) NOT NULL,
    expected_classification VARCHAR(24) NOT NULL,
    expected_minutes INTEGER NOT NULL CHECK (expected_minutes >= 0),
    PRIMARY KEY (
        snapshot_id,
        employee_id,
        work_date,
        project_allocation_id
    )
);

CREATE INDEX idx_shift_policy_org_effective
    ON workforce_shift_policy_versions(organization_id, valid_from, valid_to);
CREATE INDEX idx_employee_shift_effective
    ON employee_shift_assignments(employee_id, valid_from, valid_to);
CREATE INDEX idx_roster_snapshot_month
    ON workforce_roster_snapshot_versions(engagement_month_id, version DESC);
CREATE INDEX idx_roster_snapshot_days_employee_date
    ON workforce_roster_snapshot_days(employee_id, work_date);

CREATE TRIGGER shift_policy_versions_immutable
BEFORE UPDATE OR DELETE ON workforce_shift_policy_versions
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TRIGGER employee_shift_assignments_immutable
BEFORE UPDATE OR DELETE ON employee_shift_assignments
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TRIGGER roster_snapshot_versions_immutable
BEFORE UPDATE OR DELETE ON workforce_roster_snapshot_versions
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TRIGGER roster_snapshot_days_immutable
BEFORE UPDATE OR DELETE ON workforce_roster_snapshot_days
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

ALTER TABLE workforce_shift_policy_versions OWNER TO vms_migration_owner;
ALTER TABLE employee_shift_assignments OWNER TO vms_migration_owner;
ALTER TABLE workforce_roster_snapshot_versions OWNER TO vms_migration_owner;
ALTER TABLE workforce_roster_snapshot_days OWNER TO vms_migration_owner;
ALTER FUNCTION enforce_employee_shift_tenant() OWNER TO vms_migration_owner;

REVOKE ALL ON FUNCTION enforce_employee_shift_tenant() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION enforce_employee_shift_tenant()
TO vms_app_runtime, vms_migration_owner;

REVOKE ALL ON TABLE
    workforce_shift_policy_versions,
    employee_shift_assignments,
    workforce_roster_snapshot_versions,
    workforce_roster_snapshot_days
FROM PUBLIC, vms_reporting, vms_job_worker, vms_migration_processor;

GRANT SELECT, INSERT ON TABLE
    workforce_shift_policy_versions,
    employee_shift_assignments,
    workforce_roster_snapshot_versions,
    workforce_roster_snapshot_days
TO vms_app_runtime;

GRANT SELECT ON TABLE
    workforce_shift_policy_versions,
    employee_shift_assignments,
    workforce_roster_snapshot_versions,
    workforce_roster_snapshot_days
TO vms_reporting, vms_backup;
