CREATE TABLE migration_retro_actions (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES migration_retro_requests(id),
    request_version BIGINT NOT NULL CHECK (request_version > 0),
    action VARCHAR(24) NOT NULL CHECK (
        action IN ('APPROVED', 'REJECTED', 'CANCELLED')),
    actor_subject VARCHAR(255) NOT NULL,
    actor_role VARCHAR(64) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    correlation_id UUID NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (actor_subject, idempotency_key)
);

ALTER TABLE migration_jobs
    ADD COLUMN declared_source_type VARCHAR(32),
    ADD COLUMN declared_confidence VARCHAR(16),
    ADD COLUMN source_description VARCHAR(300),
    ADD CONSTRAINT ck_migration_job_declared_source CHECK (
        declared_source_type IS NULL OR declared_source_type IN (
            'GREYTHR_EXPORT', 'LINEAR_API', 'LINEAR_EXPORT',
            'ORIGINAL_EMAIL', 'SIGNED_DOCUMENT', 'APPROVED_SPREADSHEET',
            'MANUAL_RECONSTRUCTION', 'OTHER')),
    ADD CONSTRAINT ck_migration_job_declared_confidence CHECK (
        declared_confidence IS NULL OR declared_confidence IN (
            'HIGH', 'MEDIUM', 'LOW', 'UNVERIFIED'));

CREATE INDEX idx_migration_retro_actions_request
    ON migration_retro_actions(request_id, request_version);

CREATE OR REPLACE FUNCTION migration_retro_action_immutable()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    RAISE EXCEPTION 'Migration retro actions are immutable'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER migration_retro_actions_immutable
BEFORE UPDATE OR DELETE ON migration_retro_actions
FOR EACH ROW EXECUTE FUNCTION migration_retro_action_immutable();

ALTER TABLE migration_retro_requests
    ADD CONSTRAINT ck_migration_retro_decision_consistency CHECK (
        (state = 'PENDING'
            AND decided_by_subject IS NULL
            AND decision_at IS NULL
            AND decision_reason IS NULL)
        OR
        (state IN ('APPROVED', 'REJECTED', 'CANCELLED')
            AND decided_by_subject IS NOT NULL
            AND decision_at IS NOT NULL
            AND decision_reason IS NOT NULL)
    );
