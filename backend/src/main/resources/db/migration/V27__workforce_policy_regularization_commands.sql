CREATE TABLE employee_policy_assignment_commands (
    id UUID PRIMARY KEY,
    employee_id UUID NOT NULL REFERENCES employees(id),
    calendar_version_id UUID NOT NULL REFERENCES working_calendar_versions(id),
    leave_type_id UUID NOT NULL REFERENCES leave_types(id),
    opening_units NUMERIC(8,2) NOT NULL CHECK (opening_units > 0),
    effective_from DATE NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL CHECK (btrim(idempotency_key) <> ''),
    reason TEXT NOT NULL CHECK (btrim(reason) <> ''),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_subject VARCHAR(255) NOT NULL CHECK (btrim(created_by_subject) <> ''),
    UNIQUE (employee_id, idempotency_key)
);

CREATE TABLE attendance_regularization_decisions (
    id UUID PRIMARY KEY,
    regularization_id UUID NOT NULL UNIQUE
        REFERENCES attendance_regularizations(id),
    decision VARCHAR(16) NOT NULL CHECK (decision IN ('APPROVE', 'REJECT')),
    adjusted_net_minutes INTEGER CHECK (adjusted_net_minutes >= 0),
    reasoning TEXT NOT NULL CHECK (btrim(reasoning) <> ''),
    decided_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_by_subject VARCHAR(255) NOT NULL
        CHECK (btrim(decided_by_subject) <> ''),
    CHECK (
        (decision = 'APPROVE' AND adjusted_net_minutes IS NOT NULL)
        OR (decision = 'REJECT' AND adjusted_net_minutes IS NULL)
    )
);

CREATE TABLE attendance_regularization_adjustments (
    id UUID PRIMARY KEY,
    regularization_id UUID NOT NULL UNIQUE
        REFERENCES attendance_regularizations(id),
    employee_id UUID NOT NULL REFERENCES employees(id),
    work_date DATE NOT NULL,
    adjustment_version INTEGER NOT NULL CHECK (adjustment_version > 0),
    supersedes_adjustment_id UUID
        CONSTRAINT fk_regularization_adjustment_predecessor
        REFERENCES attendance_regularization_adjustments(id),
    adjusted_net_minutes INTEGER NOT NULL CHECK (adjusted_net_minutes >= 0),
    reason TEXT NOT NULL CHECK (btrim(reason) <> ''),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    recorded_by_subject VARCHAR(255) NOT NULL
        CHECK (btrim(recorded_by_subject) <> ''),
    UNIQUE (employee_id, work_date, adjustment_version),
    CHECK (
        (adjustment_version = 1 AND supersedes_adjustment_id IS NULL)
        OR (adjustment_version > 1 AND supersedes_adjustment_id IS NOT NULL)
    )
);

CREATE TABLE workforce_audit_events (
    id UUID PRIMARY KEY,
    object_type VARCHAR(64) NOT NULL CHECK (btrim(object_type) <> ''),
    object_id UUID NOT NULL,
    employee_id UUID REFERENCES employees(id),
    action VARCHAR(96) NOT NULL CHECK (btrim(action) <> ''),
    actor_subject VARCHAR(255) NOT NULL CHECK (btrim(actor_subject) <> ''),
    facts JSONB NOT NULL DEFAULT '{}'::jsonb,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TRIGGER employee_policy_assignment_commands_immutable
BEFORE UPDATE OR DELETE ON employee_policy_assignment_commands
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

CREATE TRIGGER attendance_regularization_decisions_immutable
BEFORE UPDATE OR DELETE ON attendance_regularization_decisions
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

CREATE TRIGGER attendance_regularization_adjustments_immutable
BEFORE UPDATE OR DELETE ON attendance_regularization_adjustments
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

CREATE TRIGGER workforce_audit_events_immutable
BEFORE UPDATE OR DELETE ON workforce_audit_events
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

ALTER TABLE employee_policy_assignment_commands OWNER TO vms_migration_owner;
ALTER TABLE attendance_regularization_decisions OWNER TO vms_migration_owner;
ALTER TABLE attendance_regularization_adjustments OWNER TO vms_migration_owner;
ALTER TABLE workforce_audit_events OWNER TO vms_migration_owner;

REVOKE ALL ON TABLE
    employee_policy_assignment_commands,
    attendance_regularization_decisions,
    attendance_regularization_adjustments,
    workforce_audit_events
FROM PUBLIC, vms_reporting, vms_job_worker, vms_migration_processor;

GRANT SELECT, INSERT ON TABLE
    employee_policy_assignment_commands,
    attendance_regularization_decisions,
    attendance_regularization_adjustments,
    workforce_audit_events
TO vms_app_runtime;

GRANT SELECT ON TABLE
    employee_policy_assignment_commands,
    attendance_regularization_decisions,
    attendance_regularization_adjustments,
    workforce_audit_events
TO vms_backup;
