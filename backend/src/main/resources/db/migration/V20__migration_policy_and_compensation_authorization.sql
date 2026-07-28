-- F06 P1 hardening: commit policy is immutable and compensation requires an
-- exact, persisted COMPENSATE action for the same migration job.

CREATE OR REPLACE FUNCTION f06_job_commit_policy_immutable()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.partial_commit IS DISTINCT FROM OLD.partial_commit THEN
        RAISE EXCEPTION 'migration job commit policy is immutable'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER migration_job_commit_policy_immutable
BEFORE UPDATE ON migration_jobs
FOR EACH ROW EXECUTE FUNCTION f06_job_commit_policy_immutable();

CREATE OR REPLACE FUNCTION f06_compensation_delete_authorized(
    p_domain_table TEXT,
    p_domain_record_id UUID
)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM migration_rollback_actions action
        JOIN migration_domain_provenance provenance
          ON provenance.job_id = action.job_id
        WHERE action.id::text =
              NULLIF(current_setting('vms.migration_compensation', TRUE), '')
          AND action.action = 'COMPENSATE'
          AND provenance.active
          AND provenance.domain_table = p_domain_table
          AND provenance.domain_record_id = p_domain_record_id
    );
$$;

CREATE OR REPLACE FUNCTION reject_immutable_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE'
       AND f06_compensation_delete_authorized(TG_TABLE_NAME, OLD.id) THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME USING ERRCODE = '55000';
END;
$$;

CREATE OR REPLACE FUNCTION f05_reject_immutable_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE'
       AND f06_compensation_delete_authorized(TG_TABLE_NAME, OLD.id) THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'F05 historical record is immutable'
        USING ERRCODE = '23514';
END;
$$;
