ALTER TABLE working_calendar_versions
    ADD COLUMN missing_checkout_cutoff_local_time TIME NOT NULL DEFAULT TIME '23:59';

CREATE OR REPLACE FUNCTION enforce_attendance_source_capability()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    employee_organization_id UUID;
BEGIN
    SELECT organization_id INTO employee_organization_id
    FROM employees
    WHERE id = NEW.employee_id;

    IF NEW.mode = 'INTERNAL_AUTHORITATIVE' AND NEW.authoritative_source <> 'INTERNAL' THEN
        RAISE EXCEPTION 'Internal authoritative mode requires INTERNAL source'
            USING ERRCODE = '23514';
    ELSIF NEW.mode = 'HISTORICAL_IMPORT' AND NEW.authoritative_source <> 'IMPORT' THEN
        RAISE EXCEPTION 'Historical import mode requires IMPORT source'
            USING ERRCODE = '23514';
    ELSIF NEW.mode = 'GREYTHR_AUTHORITATIVE' THEN
        IF NEW.authoritative_source <> 'GREYTHR' OR NOT EXISTS (
            SELECT 1
            FROM integration_capability_certifications certification
            WHERE certification.id = NEW.capability_certification_id
              AND certification.organization_id = employee_organization_id
              AND certification.provider = 'GREYTHR'
              AND certification.status = 'CERTIFIED'
        ) THEN
            RAISE EXCEPTION 'greytHR authority requires a certified same-organization capability'
                USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.mode = 'HYBRID_TRANSITION'
          AND NEW.authoritative_source = 'GREYTHR'
          AND NOT EXISTS (
              SELECT 1
              FROM integration_capability_certifications certification
              WHERE certification.id = NEW.capability_certification_id
                AND certification.organization_id = employee_organization_id
                AND certification.provider = 'GREYTHR'
                AND certification.status = 'CERTIFIED'
          ) THEN
        RAISE EXCEPTION 'Hybrid greytHR authority requires a certified same-organization capability'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER attendance_source_capability_gate
BEFORE INSERT OR UPDATE OF employee_id, mode, authoritative_source, capability_certification_id
ON attendance_source_mode_assignments
FOR EACH ROW
EXECUTE FUNCTION enforce_attendance_source_capability();

ALTER TABLE attendance_snapshot_versions
    DROP CONSTRAINT attendance_snapshot_versions_status_check;
ALTER TABLE attendance_snapshot_versions
    ADD CONSTRAINT attendance_snapshot_versions_status_check
        CHECK (status IN ('CLOSED', 'REOPENED'));
