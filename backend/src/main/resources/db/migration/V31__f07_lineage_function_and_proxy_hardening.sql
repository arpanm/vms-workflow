ALTER TABLE attendance_regularization_adjustments
    ADD COLUMN supersedes_adjustment_version INTEGER;

ALTER TABLE attendance_regularization_adjustments
    DISABLE TRIGGER attendance_regularization_adjustments_immutable;

UPDATE attendance_regularization_adjustments
SET supersedes_adjustment_version = adjustment_version - 1
WHERE supersedes_adjustment_id IS NOT NULL;

ALTER TABLE attendance_regularization_adjustments
    ENABLE TRIGGER attendance_regularization_adjustments_immutable;

ALTER TABLE attendance_regularization_adjustments
    ADD CONSTRAINT uq_regularization_adjustment_scope_version
        UNIQUE (id, employee_id, work_date, adjustment_version),
    DROP CONSTRAINT fk_regularization_adjustment_predecessor,
    ADD CONSTRAINT fk_regularization_adjustment_same_scope_predecessor
        FOREIGN KEY (
            supersedes_adjustment_id, employee_id, work_date,
            supersedes_adjustment_version
        )
        REFERENCES attendance_regularization_adjustments (
            id, employee_id, work_date, adjustment_version
        ),
    ADD CONSTRAINT ck_regularization_adjustment_immediate_predecessor CHECK (
        (adjustment_version = 1
            AND supersedes_adjustment_id IS NULL
            AND supersedes_adjustment_version IS NULL)
        OR (adjustment_version > 1
            AND supersedes_adjustment_id IS NOT NULL
            AND supersedes_adjustment_version = adjustment_version - 1)
    );

CREATE OR REPLACE FUNCTION enforce_regularization_adjustment_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
DECLARE
    target_employee UUID;
    target_date DATE;
BEGIN
    SELECT employee_id, work_date
      INTO target_employee, target_date
    FROM public.attendance_regularizations
    WHERE id = NEW.regularization_id;
    IF target_employee IS NULL
       OR target_employee <> NEW.employee_id
       OR target_date <> NEW.work_date THEN
        RAISE EXCEPTION 'Regularization adjustment target scope mismatch';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER attendance_regularization_adjustment_scope_gate
BEFORE INSERT ON attendance_regularization_adjustments
FOR EACH ROW EXECUTE FUNCTION enforce_regularization_adjustment_scope();

CREATE OR REPLACE FUNCTION enforce_regularization_content_immutable()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Attendance regularization evidence is immutable';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.employee_id IS DISTINCT FROM OLD.employee_id
       OR NEW.work_date IS DISTINCT FROM OLD.work_date
       OR NEW.reason_code IS DISTINCT FROM OLD.reason_code
       OR NEW.narrative IS DISTINCT FROM OLD.narrative
       OR NEW.requested_outcome IS DISTINCT FROM OLD.requested_outcome
       OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.created_by_subject IS DISTINCT FROM OLD.created_by_subject THEN
        RAISE EXCEPTION
            'Attendance regularization content is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER attendance_regularization_content_gate
BEFORE UPDATE OR DELETE ON attendance_regularizations
FOR EACH ROW EXECUTE FUNCTION enforce_regularization_content_immutable();

ALTER FUNCTION enforce_regularization_adjustment_scope()
    OWNER TO vms_migration_owner;
ALTER FUNCTION enforce_regularization_content_immutable()
    OWNER TO vms_migration_owner;
REVOKE ALL ON FUNCTION enforce_regularization_adjustment_scope() FROM PUBLIC;
REVOKE ALL ON FUNCTION enforce_regularization_content_immutable() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION enforce_regularization_adjustment_scope(),
    enforce_regularization_content_immutable()
TO vms_app_runtime, vms_migration_owner;

ALTER FUNCTION delivery_commitment_outbox_content_guard()
    SET search_path = pg_catalog, public;
REVOKE ALL ON FUNCTION delivery_commitment_outbox_content_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION delivery_commitment_outbox_content_guard()
TO vms_app_runtime, vms_migration_owner;

ALTER FUNCTION enforce_linear_reconciliation_terminal()
    SET search_path = pg_catalog, public;
REVOKE ALL ON FUNCTION enforce_linear_reconciliation_terminal() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION enforce_linear_reconciliation_terminal()
TO vms_app_runtime, vms_migration_owner;

ALTER FUNCTION enforce_linear_reconciliation_job_immutable()
    SET search_path = pg_catalog, public;
REVOKE ALL ON FUNCTION enforce_linear_reconciliation_job_immutable()
FROM PUBLIC;
GRANT EXECUTE ON FUNCTION enforce_linear_reconciliation_job_immutable()
TO vms_app_runtime, vms_migration_owner;
