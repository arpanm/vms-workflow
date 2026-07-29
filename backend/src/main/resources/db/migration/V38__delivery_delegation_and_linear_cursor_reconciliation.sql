-- F03 local completion: preserve delegated approval evidence and make
-- scheduled provider reconciliation bounded, cursor based, and auditable.

ALTER TABLE delivery_plan_approvals
    ADD COLUMN acting_subject VARCHAR(255),
    ADD COLUMN delegation_id UUID REFERENCES delegations(id),
    ADD COLUMN delegated_from_subject VARCHAR(255);

UPDATE delivery_plan_approvals
SET acting_subject = approver_subject
WHERE acting_subject IS NULL;

ALTER TABLE delivery_plan_approvals
    ALTER COLUMN acting_subject SET NOT NULL,
    ADD CONSTRAINT ck_delivery_approval_delegation_evidence CHECK (
        (delegation_id IS NULL AND delegated_from_subject IS NULL
            AND acting_subject = approver_subject)
        OR
        (delegation_id IS NOT NULL AND delegated_from_subject = approver_subject
            AND acting_subject <> approver_subject)
    );

CREATE OR REPLACE FUNCTION default_delivery_approval_actor()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF NEW.acting_subject IS NULL THEN
        NEW.acting_subject := NEW.approver_subject;
    END IF;
    IF NEW.delegation_id IS NOT NULL AND NOT EXISTS (
        SELECT 1
        FROM delegations delegation
        JOIN user_profiles delegate
          ON delegate.id = delegation.delegate_user_id
         AND delegate.identity_subject = NEW.acting_subject
         AND delegate.status = 'ACTIVE'
        JOIN user_profiles delegator
          ON delegator.id = delegation.delegator_user_id
         AND delegator.identity_subject = NEW.approver_subject
         AND delegator.identity_subject = NEW.delegated_from_subject
         AND delegator.status = 'ACTIVE'
        JOIN delivery_plan_versions version
          ON version.id = NEW.plan_version_id
        JOIN delivery_plans plan ON plan.id = version.plan_id
        JOIN engagement_months month
          ON month.id = plan.engagement_month_id
        WHERE delegation.id = NEW.delegation_id
          AND delegation.engagement_id = month.engagement_id
          AND delegation.status = 'ACTIVE'
          AND CURRENT_TIMESTAMP >= delegation.valid_from
          AND CURRENT_TIMESTAMP < delegation.valid_to
          AND 'delivery.plan.approve' = ANY(delegation.action_codes)
          AND (
              delegation.project_id IS NULL
              OR NOT EXISTS (
                  SELECT 1
                  FROM delivery_deliverable_versions deliverable
                  WHERE deliverable.plan_version_id = NEW.plan_version_id
                    AND deliverable.project_id <> delegation.project_id
              )
          )
    ) THEN
        RAISE EXCEPTION
            'Delivery approval delegation is inactive or outside plan scope'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER delivery_approval_actor_default
BEFORE INSERT ON delivery_plan_approvals
FOR EACH ROW EXECUTE FUNCTION default_delivery_approval_actor();

CREATE TABLE linear_reconciliation_checkpoints (
    connection_id UUID PRIMARY KEY REFERENCES linear_connections(id),
    cursor_updated_at TIMESTAMPTZ,
    cursor_issue_uuid UUID,
    next_run_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_started_at TIMESTAMPTZ,
    last_completed_at TIMESTAMPTZ,
    consecutive_failures INTEGER NOT NULL DEFAULT 0
        CHECK (consecutive_failures >= 0),
    last_error_code VARCHAR(64),
    version BIGINT NOT NULL DEFAULT 0,
    CHECK (
        (cursor_updated_at IS NULL AND cursor_issue_uuid IS NULL)
        OR (cursor_updated_at IS NOT NULL AND cursor_issue_uuid IS NOT NULL)
    )
);

INSERT INTO linear_reconciliation_checkpoints (connection_id)
SELECT id FROM linear_connections
ON CONFLICT DO NOTHING;

CREATE TABLE linear_reconciliation_attempts (
    id UUID PRIMARY KEY,
    sync_job_id UUID NOT NULL REFERENCES linear_sync_jobs(id),
    connection_id UUID NOT NULL REFERENCES linear_connections(id),
    attempt_number INTEGER NOT NULL CHECK (attempt_number > 0),
    requested_cursor TEXT,
    response_cursor TEXT,
    requested_limit INTEGER NOT NULL CHECK (
        requested_limit BETWEEN 1 AND 250),
    fetched_count INTEGER NOT NULL CHECK (
        fetched_count BETWEEN 0 AND requested_limit),
    applied_count INTEGER NOT NULL CHECK (
        applied_count BETWEEN 0 AND fetched_count),
    partial_error_count INTEGER NOT NULL DEFAULT 0
        CHECK (partial_error_count >= 0),
    outcome VARCHAR(16) NOT NULL CHECK (
        outcome IN ('SUCCEEDED', 'PARTIAL', 'FAILED')),
    provider_errors JSONB NOT NULL DEFAULT '[]'::jsonb
        CHECK (jsonb_typeof(provider_errors) = 'array'),
    error_code VARCHAR(64),
    evidence_checksum VARCHAR(64) NOT NULL CHECK (
        evidence_checksum ~ '^[0-9a-f]{64}$'),
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    CHECK (completed_at >= started_at),
    CHECK (
        (outcome = 'SUCCEEDED'
            AND partial_error_count = 0 AND error_code IS NULL)
        OR (outcome = 'PARTIAL'
            AND partial_error_count > 0 AND error_code IS NULL)
        OR (outcome = 'FAILED' AND error_code IS NOT NULL)
    ),
    UNIQUE (sync_job_id, attempt_number)
);

CREATE INDEX idx_linear_reconciliation_due
    ON linear_reconciliation_checkpoints(next_run_at, connection_id);

CREATE INDEX idx_linear_reconciliation_attempts_connection
    ON linear_reconciliation_attempts(connection_id, completed_at DESC, id);

CREATE TRIGGER linear_reconciliation_attempts_immutable
BEFORE UPDATE OR DELETE ON linear_reconciliation_attempts
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

ALTER TABLE linear_reconciliation_checkpoints OWNER TO vms_migration_owner;
ALTER TABLE linear_reconciliation_attempts OWNER TO vms_migration_owner;
ALTER FUNCTION default_delivery_approval_actor() OWNER TO vms_migration_owner;

REVOKE ALL ON TABLE linear_reconciliation_checkpoints,
    linear_reconciliation_attempts
FROM PUBLIC, vms_reporting, vms_job_worker, vms_migration_processor;
GRANT SELECT, INSERT, UPDATE ON linear_reconciliation_checkpoints
TO vms_app_runtime;
GRANT SELECT, INSERT ON linear_reconciliation_attempts TO vms_app_runtime;
GRANT SELECT ON linear_reconciliation_checkpoints,
    linear_reconciliation_attempts TO vms_backup;
