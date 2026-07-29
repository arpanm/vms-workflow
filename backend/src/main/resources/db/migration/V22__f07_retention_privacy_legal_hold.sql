-- F07 local retention/privacy/legal-hold core. Schedule periods are supplied
-- by authorized configuration; this migration deliberately seeds no duration.

INSERT INTO permissions(id, code, description) VALUES
    ('10000000-0000-0000-0000-000000000061',
     'retention.schedule.manage',
     'Version organization-scoped retention schedules and inspect classification'),
    ('10000000-0000-0000-0000-000000000062',
     'retention.execute',
     'Dry-run and execute organization-scoped retention capability expiry'),
    ('10000000-0000-0000-0000-000000000063',
     'legal-hold.manage',
     'Place and release organization-scoped legal holds');

INSERT INTO role_permissions(role_id, permission_id)
SELECT role.id, permission.id
FROM roles role
CROSS JOIN permissions permission
WHERE role.code IN ('ORG_ADMIN', 'ENGAGEMENT_ADMIN', 'GOVERNANCE_REVIEWER')
  AND permission.code IN (
      'retention.schedule.manage', 'retention.execute', 'legal-hold.manage'
  )
ON CONFLICT DO NOTHING;

CREATE TABLE f07_retention_schedules (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    record_class VARCHAR(80) NOT NULL CHECK (record_class IN (
        'TEMPORARY_EXPORT_CAPABILITY', 'TEMPORARY_PACKAGE_SHARE'
    )),
    version INTEGER NOT NULL CHECK (version > 0),
    retention_days INTEGER NOT NULL CHECK (
        retention_days BETWEEN 1 AND 36500
    ),
    policy_reference VARCHAR(200) NOT NULL,
    effective_from TIMESTAMPTZ NOT NULL,
    supersedes_id UUID REFERENCES f07_retention_schedules(id),
    created_by_subject VARCHAR(255) NOT NULL,
    authority_snapshot JSONB NOT NULL,
    correlation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (organization_id, record_class, version)
);

CREATE TABLE f07_legal_holds (
    id UUID PRIMARY KEY,
    artifact_id UUID NOT NULL REFERENCES f05_private_artifacts(id),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    reason_code VARCHAR(100) NOT NULL,
    two_person_release BOOLEAN NOT NULL,
    placed_by_subject VARCHAR(255) NOT NULL,
    authority_snapshot JSONB NOT NULL,
    correlation_id UUID NOT NULL,
    placed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE f07_legal_hold_transitions (
    id UUID PRIMARY KEY,
    hold_id UUID NOT NULL REFERENCES f07_legal_holds(id),
    action VARCHAR(32) NOT NULL CHECK (action IN (
        'PLACED', 'RELEASE_REQUESTED', 'RELEASE_APPROVED'
    )),
    prior_hold BOOLEAN NOT NULL,
    effective_hold BOOLEAN NOT NULL,
    reason_code VARCHAR(100) NOT NULL,
    actor_subject VARCHAR(255) NOT NULL,
    authority_snapshot JSONB NOT NULL,
    correlation_id UUID NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (
        (action = 'PLACED' AND NOT prior_hold AND effective_hold)
        OR (action = 'RELEASE_REQUESTED' AND prior_hold AND effective_hold)
        OR (action = 'RELEASE_APPROVED' AND prior_hold AND NOT effective_hold)
    )
);
CREATE UNIQUE INDEX uq_f07_hold_placed
    ON f07_legal_hold_transitions(hold_id) WHERE action = 'PLACED';
CREATE UNIQUE INDEX uq_f07_hold_release_request
    ON f07_legal_hold_transitions(hold_id) WHERE action = 'RELEASE_REQUESTED';
CREATE UNIQUE INDEX uq_f07_hold_release
    ON f07_legal_hold_transitions(hold_id) WHERE action = 'RELEASE_APPROVED';

CREATE TABLE f07_retention_runs (
    id UUID PRIMARY KEY,
    schedule_id UUID NOT NULL REFERENCES f07_retention_schedules(id),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    record_class VARCHAR(80) NOT NULL,
    as_of TIMESTAMPTZ NOT NULL,
    requested_by_subject VARCHAR(255) NOT NULL,
    authority_snapshot JSONB NOT NULL,
    correlation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE f07_retention_run_transitions (
    id UUID PRIMARY KEY,
    transition_sequence BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    run_id UUID NOT NULL REFERENCES f07_retention_runs(id),
    status VARCHAR(32) NOT NULL CHECK (status IN (
        'DRY_RUN_COMPLETE', 'EXECUTION_STARTED', 'COMPLETED',
        'RETRY_SCHEDULED', 'DEAD_LETTER', 'RECOVERY_AUTHORIZED'
    )),
    attempt INTEGER NOT NULL CHECK (attempt >= 0),
    eligible_count INTEGER NOT NULL CHECK (eligible_count >= 0),
    skipped_count INTEGER NOT NULL CHECK (skipped_count >= 0),
    failure_count INTEGER NOT NULL CHECK (failure_count >= 0),
    next_attempt_at TIMESTAMPTZ,
    reason_code VARCHAR(100) NOT NULL,
    actor_subject VARCHAR(255) NOT NULL,
    correlation_id UUID NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (run_id, status, attempt)
);

CREATE TABLE f07_retention_candidates (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES f07_retention_runs(id),
    target_type VARCHAR(32) NOT NULL CHECK (target_type IN (
        'REPORT_EXPORT', 'PACKAGE_SHARE'
    )),
    target_id UUID NOT NULL,
    artifact_id UUID,
    deadline TIMESTAMPTZ NOT NULL,
    decision VARCHAR(32) NOT NULL CHECK (decision IN (
        'ELIGIBLE', 'HELD', 'NOT_DUE'
    )),
    reason_code VARCHAR(100) NOT NULL,
    classification VARCHAR(32) NOT NULL CHECK (
        classification IN ('INTERNAL', 'CONFIDENTIAL', 'RESTRICTED')
    ),
    source_hash VARCHAR(64) NOT NULL,
    evidence_preserved BOOLEAN NOT NULL DEFAULT TRUE,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (run_id, target_type, target_id),
    CHECK (source_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE f07_retention_execution_results (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES f07_retention_runs(id),
    candidate_id UUID NOT NULL REFERENCES f07_retention_candidates(id),
    attempt INTEGER NOT NULL CHECK (attempt > 0),
    outcome VARCHAR(32) NOT NULL CHECK (outcome IN (
        'CAPABILITY_EXPIRED', 'SKIPPED_HELD', 'SKIPPED_STATE_CHANGED',
        'ALREADY_APPLIED', 'FAILED'
    )),
    reason_code VARCHAR(100) NOT NULL,
    actor_subject VARCHAR(255) NOT NULL,
    correlation_id UUID NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (candidate_id, attempt)
);

-- Mutable operational lease state is deliberately separate from immutable
-- transition evidence. The owner heartbeat prevents elapsed retry delay from
-- allowing a second executor to overlap a still-running candidate batch.
CREATE TABLE f07_retention_execution_leases (
    run_id UUID PRIMARY KEY REFERENCES f07_retention_runs(id),
    owner_id UUID NOT NULL,
    attempt INTEGER NOT NULL CHECK (attempt > 0),
    acquired_at TIMESTAMPTZ NOT NULL,
    heartbeat_at TIMESTAMPTZ NOT NULL,
    lease_expires_at TIMESTAMPTZ NOT NULL,
    CHECK (lease_expires_at > heartbeat_at)
);

CREATE TABLE f07_retention_proofs (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES f07_retention_runs(id),
    candidate_id UUID NOT NULL UNIQUE REFERENCES f07_retention_candidates(id),
    proof_type VARCHAR(32) NOT NULL CHECK (
        proof_type = 'CAPABILITY_EXPIRY'
    ),
    target_type VARCHAR(32) NOT NULL,
    target_id UUID NOT NULL,
    source_hash VARCHAR(64) NOT NULL,
    content_deleted BOOLEAN NOT NULL DEFAULT FALSE
        CHECK (NOT content_deleted),
    closed_evidence_preserved BOOLEAN NOT NULL DEFAULT TRUE
        CHECK (closed_evidence_preserved),
    expired_by_subject VARCHAR(255) NOT NULL,
    correlation_id UUID NOT NULL,
    expired_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (source_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE f07_data_classification_inventory (
    id UUID PRIMARY KEY,
    asset_type VARCHAR(24) NOT NULL CHECK (asset_type IN (
        'TABLE', 'API', 'LOG', 'EXPORT', 'ARTIFACT', 'PROHIBITED_FIELD'
    )),
    asset_name VARCHAR(200) NOT NULL,
    classification VARCHAR(32) NOT NULL CHECK (
        classification IN ('INTERNAL', 'CONFIDENTIAL', 'RESTRICTED')
    ),
    handling_rule VARCHAR(500) NOT NULL,
    retention_record_class VARCHAR(80),
    prohibited_commercial_data BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (asset_type, asset_name)
);

INSERT INTO f07_data_classification_inventory(
    id, asset_type, asset_name, classification, handling_rule,
    retention_record_class, prohibited_commercial_data
) VALUES
    ('22000000-0000-0000-0000-000000000001', 'TABLE',
     'f05_private_artifacts', 'CONFIDENTIAL',
     'Scoped metadata; object keys and actor subjects excluded from general reporting.',
     NULL, FALSE),
    ('22000000-0000-0000-0000-000000000002', 'TABLE',
     'f05_private_artifact_blobs', 'RESTRICTED',
     'Private binary content; scanner-passed and current authorization required.',
     NULL, FALSE),
    ('22000000-0000-0000-0000-000000000003', 'API',
     '/api/v1/governance/retention/**', 'CONFIDENTIAL',
     'Bearer authenticated, active organization-scoped RBAC and correlated audit.',
     NULL, FALSE),
    ('22000000-0000-0000-0000-000000000004', 'LOG',
     'security-and-retention-events', 'RESTRICTED',
     'No payload, token, email, object key or raw actor; correlation and safe codes only.',
     NULL, FALSE),
    ('22000000-0000-0000-0000-000000000005', 'EXPORT',
     'f05_report_exports', 'CONFIDENTIAL',
     'Short-lived capability; expiry proof preserves immutable closed evidence.',
     'TEMPORARY_EXPORT_CAPABILITY', FALSE),
    ('22000000-0000-0000-0000-000000000006', 'ARTIFACT',
     'evidence_package', 'RESTRICTED',
     'Immutable closed evidence is never deleted by local capability retention.',
     NULL, FALSE),
    ('22000000-0000-0000-0000-000000000007', 'ARTIFACT',
     'package_share', 'CONFIDENTIAL',
     'Recipient capability expires or is revoked without deleting package evidence.',
     'TEMPORARY_PACKAGE_SHARE', FALSE),
    ('22000000-0000-0000-0000-000000000008', 'PROHIBITED_FIELD',
     'salary', 'RESTRICTED', 'Reject before persistence, export or logging.',
     NULL, TRUE),
    ('22000000-0000-0000-0000-000000000009', 'PROHIBITED_FIELD',
     'rate', 'RESTRICTED', 'Reject billing, cost, hourly and commercial rate fields.',
     NULL, TRUE),
    ('22000000-0000-0000-0000-000000000010', 'PROHIBITED_FIELD',
     'markup', 'RESTRICTED', 'Reject before persistence, export or logging.',
     NULL, TRUE);

CREATE OR REPLACE FUNCTION f07_reject_immutable_change()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    RAISE EXCEPTION 'F07 governance evidence is append-only'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER f07_retention_schedules_immutable
BEFORE UPDATE OR DELETE ON f07_retention_schedules
FOR EACH ROW EXECUTE FUNCTION f07_reject_immutable_change();
CREATE TRIGGER f07_legal_holds_immutable
BEFORE UPDATE OR DELETE ON f07_legal_holds
FOR EACH ROW EXECUTE FUNCTION f07_reject_immutable_change();
CREATE TRIGGER f07_legal_hold_transitions_immutable
BEFORE UPDATE OR DELETE ON f07_legal_hold_transitions
FOR EACH ROW EXECUTE FUNCTION f07_reject_immutable_change();
CREATE TRIGGER f07_retention_runs_immutable
BEFORE UPDATE OR DELETE ON f07_retention_runs
FOR EACH ROW EXECUTE FUNCTION f07_reject_immutable_change();
CREATE TRIGGER f07_retention_run_transitions_immutable
BEFORE UPDATE OR DELETE ON f07_retention_run_transitions
FOR EACH ROW EXECUTE FUNCTION f07_reject_immutable_change();
CREATE TRIGGER f07_retention_candidates_immutable
BEFORE UPDATE OR DELETE ON f07_retention_candidates
FOR EACH ROW EXECUTE FUNCTION f07_reject_immutable_change();
CREATE TRIGGER f07_retention_results_immutable
BEFORE UPDATE OR DELETE ON f07_retention_execution_results
FOR EACH ROW EXECUTE FUNCTION f07_reject_immutable_change();
CREATE TRIGGER f07_retention_proofs_immutable
BEFORE UPDATE OR DELETE ON f07_retention_proofs
FOR EACH ROW EXECUTE FUNCTION f07_reject_immutable_change();
CREATE TRIGGER f07_classification_inventory_immutable
BEFORE UPDATE OR DELETE ON f07_data_classification_inventory
FOR EACH ROW EXECUTE FUNCTION f07_reject_immutable_change();

CREATE OR REPLACE FUNCTION f07_validate_hold_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
DECLARE
    hold_row f07_legal_holds%ROWTYPE;
    request_actor TEXT;
BEGIN
    SELECT * INTO hold_row FROM f07_legal_holds WHERE id = NEW.hold_id;
    IF NEW.action = 'RELEASE_REQUESTED' AND NOT hold_row.two_person_release THEN
        RAISE EXCEPTION 'Release request is only valid for dual-control holds'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.action = 'RELEASE_APPROVED' AND hold_row.two_person_release THEN
        SELECT actor_subject INTO request_actor
        FROM f07_legal_hold_transitions
        WHERE hold_id = NEW.hold_id AND action = 'RELEASE_REQUESTED';
        IF request_actor IS NULL OR request_actor = NEW.actor_subject THEN
            RAISE EXCEPTION 'Legal-hold release requires a second actor'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f07_validate_hold_transition
BEFORE INSERT ON f07_legal_hold_transitions
FOR EACH ROW EXECUTE FUNCTION f07_validate_hold_transition();

CREATE OR REPLACE FUNCTION f07_guard_artifact_hold_release()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF OLD.legal_hold AND NOT NEW.legal_hold
       AND EXISTS (
           SELECT 1
           FROM f07_legal_holds hold
           WHERE hold.artifact_id = NEW.id
             AND NOT EXISTS (
                 SELECT 1
                 FROM f07_legal_hold_transitions transition
                 WHERE transition.hold_id = hold.id
                   AND transition.action = 'RELEASE_APPROVED'
             )
       )
    THEN
        RAISE EXCEPTION
            'F07 legal hold requires its governed release transition'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f07_guard_artifact_hold_release
BEFORE UPDATE OF legal_hold ON f05_private_artifacts
FOR EACH ROW EXECUTE FUNCTION f07_guard_artifact_hold_release();

CREATE OR REPLACE FUNCTION f07_assert_no_commercial_fields(value JSONB)
RETURNS BOOLEAN
LANGUAGE plpgsql
IMMUTABLE
SET search_path = pg_catalog, public
AS $$
DECLARE
    item RECORD;
    normalized_key TEXT;
BEGIN
    IF value IS NULL THEN
        RETURN TRUE;
    END IF;
    IF jsonb_typeof(value) = 'object' THEN
        FOR item IN
            SELECT entry.key, entry.value AS nested
            FROM jsonb_each(value) entry
        LOOP
            normalized_key := regexp_replace(
                lower(item.key), '[^a-z0-9]', '', 'g');
            IF normalized_key ~ '(salary|markup)'
               OR normalized_key ~
                  '^(rate|rates|ratecard|rateamount|ratepercent|ratevalue|rateband)$'
               OR normalized_key ~
                  '^(billing|cost|hourly|commercial|vendor|client|employee).*rate'
            THEN
                RAISE EXCEPTION 'Prohibited commercial field'
                    USING ERRCODE = '22023';
            END IF;
            PERFORM f07_assert_no_commercial_fields(item.nested);
        END LOOP;
    ELSIF jsonb_typeof(value) = 'array' THEN
        FOR item IN
            SELECT entry.value AS nested
            FROM jsonb_array_elements(value) entry
        LOOP
            PERFORM f07_assert_no_commercial_fields(item.nested);
        END LOOP;
    END IF;
    RETURN TRUE;
END;
$$;

ALTER TABLE f05_report_exports
    ADD CONSTRAINT ck_f07_export_filters_no_commercial_data
    CHECK (f07_assert_no_commercial_fields(filters));

GRANT SELECT, INSERT ON
    f07_retention_schedules,
    f07_legal_holds,
    f07_legal_hold_transitions,
    f07_retention_runs,
    f07_retention_run_transitions,
    f07_retention_candidates,
    f07_retention_execution_results,
    f07_retention_execution_leases,
    f07_retention_proofs
TO vms_app_runtime;
GRANT UPDATE, DELETE ON f07_retention_execution_leases
TO vms_app_runtime;
GRANT USAGE, SELECT ON SEQUENCE
    f07_retention_run_transitions_transition_sequence_seq
TO vms_app_runtime;
GRANT SELECT ON f07_data_classification_inventory TO vms_app_runtime;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO vms_backup;
REVOKE EXECUTE ON FUNCTION
    f07_reject_immutable_change(),
    f07_validate_hold_transition(),
    f07_guard_artifact_hold_release(),
    f07_assert_no_commercial_fields(JSONB)
FROM PUBLIC;
GRANT EXECUTE ON FUNCTION f07_assert_no_commercial_fields(JSONB)
TO vms_app_runtime;

ALTER TABLE f07_retention_schedules OWNER TO vms_migration_owner;
ALTER TABLE f07_legal_holds OWNER TO vms_migration_owner;
ALTER TABLE f07_legal_hold_transitions OWNER TO vms_migration_owner;
ALTER TABLE f07_retention_runs OWNER TO vms_migration_owner;
ALTER TABLE f07_retention_run_transitions OWNER TO vms_migration_owner;
ALTER TABLE f07_retention_candidates OWNER TO vms_migration_owner;
ALTER TABLE f07_retention_execution_results OWNER TO vms_migration_owner;
ALTER TABLE f07_retention_execution_leases OWNER TO vms_migration_owner;
ALTER TABLE f07_retention_proofs OWNER TO vms_migration_owner;
ALTER TABLE f07_data_classification_inventory
    OWNER TO vms_migration_owner;
ALTER FUNCTION f07_reject_immutable_change()
    OWNER TO vms_migration_owner;
ALTER FUNCTION f07_validate_hold_transition()
    OWNER TO vms_migration_owner;
ALTER FUNCTION f07_guard_artifact_hold_release()
    OWNER TO vms_migration_owner;
ALTER FUNCTION f07_assert_no_commercial_fields(JSONB)
    OWNER TO vms_migration_owner;
