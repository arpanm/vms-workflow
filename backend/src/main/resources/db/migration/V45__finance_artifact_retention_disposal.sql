-- Governed F05 binary retention extends the organization-scoped, versioned
-- schedule/dry-run/execution model introduced by V22. This migration seeds no
-- duration: content remains fail-closed until an authorized schedule exists.

ALTER TABLE f07_retention_schedules
    DROP CONSTRAINT f07_retention_schedules_record_class_check;
ALTER TABLE f07_retention_schedules
    ADD CONSTRAINT f07_retention_schedules_record_class_check CHECK (
        record_class IN (
            'TEMPORARY_EXPORT_CAPABILITY', 'TEMPORARY_PACKAGE_SHARE',
            'FINANCE_EXPORT_CONTENT', 'FINANCE_EVIDENCE_CONTENT'
        )
    );

ALTER TABLE f07_retention_candidates
    DROP CONSTRAINT f07_retention_candidates_target_type_check;
ALTER TABLE f07_retention_candidates
    ADD CONSTRAINT f07_retention_candidates_target_type_check CHECK (
        target_type IN (
            'REPORT_EXPORT', 'PACKAGE_SHARE', 'FINANCE_ARTIFACT'
        )
    );
ALTER TABLE f07_retention_candidates
    DROP CONSTRAINT f07_retention_candidates_decision_check;
ALTER TABLE f07_retention_candidates
    ADD CONSTRAINT f07_retention_candidates_decision_check CHECK (
        decision IN ('ELIGIBLE', 'HELD', 'REFERENCED', 'NOT_DUE')
    );

ALTER TABLE f07_retention_execution_results
    DROP CONSTRAINT f07_retention_execution_results_outcome_check;
ALTER TABLE f07_retention_execution_results
    ADD CONSTRAINT f07_retention_execution_results_outcome_check CHECK (
        outcome IN (
            'CAPABILITY_EXPIRED', 'CONTENT_DISPOSED', 'SKIPPED_HELD',
            'SKIPPED_REFERENCED', 'SKIPPED_STATE_CHANGED',
            'ALREADY_APPLIED', 'FAILED'
        )
    );

ALTER TABLE f07_retention_proofs
    DROP CONSTRAINT f07_retention_proofs_proof_type_check,
    DROP CONSTRAINT f07_retention_proofs_content_deleted_check;
ALTER TABLE f07_retention_proofs
    ADD CONSTRAINT f07_retention_proofs_proof_type_check CHECK (
        proof_type IN ('CAPABILITY_EXPIRY', 'CONTENT_DISPOSAL')
    ),
    ADD CONSTRAINT f07_retention_proofs_content_deleted_check CHECK (
        (proof_type = 'CAPABILITY_EXPIRY' AND NOT content_deleted)
        OR (proof_type = 'CONTENT_DISPOSAL' AND content_deleted)
    );

ALTER TABLE f05_private_artifacts
    ADD COLUMN retention_status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE'
        CHECK (retention_status IN ('ACTIVE', 'DISPOSED')),
    ADD COLUMN disposed_at TIMESTAMPTZ,
    ADD COLUMN disposed_by_subject VARCHAR(255),
    ADD COLUMN disposal_reason_code VARCHAR(100),
    ADD CONSTRAINT ck_f05_artifact_disposal_state CHECK (
        (retention_status = 'ACTIVE'
            AND disposed_at IS NULL
            AND disposed_by_subject IS NULL
            AND disposal_reason_code IS NULL)
        OR
        (retention_status = 'DISPOSED'
            AND disposed_at IS NOT NULL
            AND disposed_by_subject IS NOT NULL
            AND disposal_reason_code IS NOT NULL)
    );

CREATE TABLE f05_artifact_retention_transitions (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES f07_retention_runs(id),
    candidate_id UUID NOT NULL UNIQUE
        REFERENCES f07_retention_candidates(id),
    artifact_id UUID NOT NULL REFERENCES f05_private_artifacts(id),
    schedule_id UUID NOT NULL REFERENCES f07_retention_schedules(id),
    action VARCHAR(32) NOT NULL CHECK (action = 'DISPOSED'),
    prior_status VARCHAR(24) NOT NULL CHECK (prior_status = 'ACTIVE'),
    effective_status VARCHAR(24) NOT NULL CHECK (
        effective_status = 'DISPOSED'
    ),
    reason_code VARCHAR(100) NOT NULL,
    actor_subject VARCHAR(255) NOT NULL,
    correlation_id UUID NOT NULL,
    database_transaction_id BIGINT NOT NULL DEFAULT txid_current(),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_f05_retention_due
    ON f05_private_artifacts(
        owner_organization_id, retention_status, retention_class,
        recorded_at, id
    );
CREATE INDEX idx_f05_retention_transition_artifact
    ON f05_artifact_retention_transitions(artifact_id, recorded_at DESC);

CREATE TRIGGER f05_artifact_retention_transitions_immutable
BEFORE UPDATE OR DELETE ON f05_artifact_retention_transitions
FOR EACH ROW EXECUTE FUNCTION f05_reject_immutable_change();

CREATE OR REPLACE FUNCTION f05_guard_artifact_blob_change()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF TG_OP <> 'DELETE'
       OR NOT EXISTS (
           SELECT 1
           FROM f05_artifact_retention_transitions transition
           WHERE transition.artifact_id = OLD.artifact_id
             AND transition.action = 'DISPOSED'
             AND transition.database_transaction_id = txid_current()
       )
    THEN
        RAISE EXCEPTION 'F05 private artifact bytes are immutable outside governed retention'
            USING ERRCODE = '55000';
    END IF;
    RETURN OLD;
END;
$$;

DROP TRIGGER f05_artifact_blobs_immutable ON f05_private_artifact_blobs;
CREATE TRIGGER f05_artifact_blobs_retention_guard
BEFORE UPDATE OR DELETE ON f05_private_artifact_blobs
FOR EACH ROW EXECUTE FUNCTION f05_guard_artifact_blob_change();

CREATE OR REPLACE FUNCTION f05_guard_artifact_retention_change()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'F05 artifact metadata cannot be deleted'
            USING ERRCODE = '55000';
    END IF;
    IF OLD.retention_status <> NEW.retention_status
       AND (
           OLD.retention_status <> 'ACTIVE'
           OR NEW.retention_status <> 'DISPOSED'
           OR OLD.legal_hold
           OR NOT EXISTS (
               SELECT 1
               FROM f05_artifact_retention_transitions transition
               JOIN f07_retention_candidates candidate
                 ON candidate.id = transition.candidate_id
               JOIN f07_retention_runs run ON run.id = transition.run_id
               WHERE transition.artifact_id = NEW.id
                 AND candidate.artifact_id = NEW.id
                 AND candidate.decision = 'ELIGIBLE'
                 AND run.schedule_id = transition.schedule_id
                 AND transition.database_transaction_id = txid_current()
                 AND transition.actor_subject = NEW.disposed_by_subject
                 AND transition.reason_code = NEW.disposal_reason_code
                 AND transition.recorded_at = NEW.disposed_at
           )
       )
    THEN
        RAISE EXCEPTION 'Illegal F05 artifact retention transition'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.retention_status = NEW.retention_status
       AND (
           OLD.disposed_at IS DISTINCT FROM NEW.disposed_at
           OR OLD.disposed_by_subject IS DISTINCT FROM NEW.disposed_by_subject
           OR OLD.disposal_reason_code IS DISTINCT FROM NEW.disposal_reason_code
       )
    THEN
        RAISE EXCEPTION 'F05 artifact disposal metadata is immutable'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER f05_private_artifact_retention_guard
BEFORE UPDATE OR DELETE ON f05_private_artifacts
FOR EACH ROW EXECUTE FUNCTION f05_guard_artifact_retention_change();

ALTER TABLE f05_artifact_retention_transitions
    OWNER TO vms_migration_owner;
ALTER FUNCTION f05_guard_artifact_blob_change()
    OWNER TO vms_migration_owner;
ALTER FUNCTION f05_guard_artifact_retention_change()
    OWNER TO vms_migration_owner;

REVOKE ALL ON f05_artifact_retention_transitions
FROM PUBLIC, vms_reporting, vms_job_worker, vms_migration_processor;
GRANT SELECT, INSERT ON f05_artifact_retention_transitions
TO vms_app_runtime;
GRANT UPDATE (
    retention_status, disposed_at, disposed_by_subject, disposal_reason_code
) ON f05_private_artifacts TO vms_app_runtime;
GRANT DELETE ON f05_private_artifact_blobs TO vms_app_runtime;
GRANT SELECT ON f05_artifact_retention_transitions TO vms_backup;

REVOKE ALL ON FUNCTION
    f05_guard_artifact_blob_change(),
    f05_guard_artifact_retention_change()
FROM PUBLIC, vms_reporting, vms_job_worker, vms_migration_processor;
GRANT EXECUTE ON FUNCTION
    f05_guard_artifact_blob_change(),
    f05_guard_artifact_retention_change()
TO vms_app_runtime;

DROP TRIGGER f07_classification_inventory_immutable
    ON f07_data_classification_inventory;
UPDATE f07_data_classification_inventory
SET retention_record_class = 'FINANCE_EVIDENCE_CONTENT'
WHERE asset_name IN ('f05_private_artifacts', 'f05_private_artifact_blobs');
CREATE TRIGGER f07_classification_inventory_immutable
BEFORE UPDATE OR DELETE ON f07_data_classification_inventory
FOR EACH ROW EXECUTE FUNCTION f07_reject_immutable_change();
