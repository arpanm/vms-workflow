-- Evidence metadata remains immutable except for the scanner's one-way,
-- provider-neutral PENDING -> terminal result transition.
DROP TRIGGER IF EXISTS f04_evidence_artifacts_immutable ON evidence_artifacts;

CREATE OR REPLACE FUNCTION f04_evidence_artifact_transition_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF OLD.scan_status <> 'PENDING'
       OR NEW.scan_status NOT IN ('PASSED', 'FAILED', 'UNKNOWN')
       OR (NEW.scan_status IN ('PASSED', 'FAILED')
           AND NEW.provider_status <> 'AVAILABLE')
       OR (NEW.scan_status = 'UNKNOWN'
           AND NEW.provider_status <> 'ACTION_REQUIRED')
       OR NEW.id <> OLD.id
       OR NEW.engagement_id <> OLD.engagement_id
       OR NEW.engagement_month_id IS DISTINCT FROM OLD.engagement_month_id
       OR NEW.artifact_kind <> OLD.artifact_kind
       OR NEW.object_key IS DISTINCT FROM OLD.object_key
       OR NEW.object_version IS DISTINCT FROM OLD.object_version
       OR NEW.reference_url IS DISTINCT FROM OLD.reference_url
       OR NEW.original_name IS DISTINCT FROM OLD.original_name
       OR NEW.safe_name <> OLD.safe_name
       OR NEW.declared_mime_type IS DISTINCT FROM OLD.declared_mime_type
       OR NEW.sniffed_mime_type IS DISTINCT FROM OLD.sniffed_mime_type
       OR NEW.size_bytes IS DISTINCT FROM OLD.size_bytes
       OR NEW.sha256 <> OLD.sha256
       OR NEW.classification <> OLD.classification
       OR NEW.retention_status <> OLD.retention_status
       OR NEW.legal_hold <> OLD.legal_hold
       OR NEW.source <> OLD.source
       OR NEW.uploader_subject IS DISTINCT FROM OLD.uploader_subject
       OR NEW.represented_at IS DISTINCT FROM OLD.represented_at
       OR NEW.recorded_at <> OLD.recorded_at THEN
        RAISE EXCEPTION 'Evidence artifact metadata is immutable outside scan transition'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER f04_evidence_artifact_transition_gate
BEFORE UPDATE ON evidence_artifacts
FOR EACH ROW EXECUTE FUNCTION f04_evidence_artifact_transition_guard();

CREATE TRIGGER f04_evidence_artifact_delete_gate
BEFORE DELETE ON evidence_artifacts
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

ALTER FUNCTION f04_evidence_artifact_transition_guard()
    OWNER TO vms_migration_owner;
REVOKE ALL ON FUNCTION f04_evidence_artifact_transition_guard()
    FROM PUBLIC, vms_reporting, vms_job_worker, vms_migration_processor;
GRANT EXECUTE ON FUNCTION f04_evidence_artifact_transition_guard()
    TO vms_app_runtime;
