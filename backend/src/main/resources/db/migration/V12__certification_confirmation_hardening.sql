-- F04 local integrity and governance hardening.
--
-- This migration is intentionally additive to V11.  It strengthens the local
-- source-of-truth without claiming that mailbox, object storage, identity
-- provider, or F05 integrations are configured.

ALTER TABLE user_profiles
    ADD COLUMN principal_type VARCHAR(16) NOT NULL DEFAULT 'HUMAN'
        CHECK (principal_type IN ('HUMAN', 'SERVICE'));

INSERT INTO permissions (id, code, description)
VALUES (
    '10000000-0000-0000-0000-000000000042',
    'certification.inbound.ingest',
    'Record authenticated provider-neutral inbound confirmation metadata'
);

INSERT INTO role_permissions (role_id, permission_id)
SELECT role.id, permission.id
FROM roles role
CROSS JOIN permissions permission
WHERE role.code IN ('ORG_ADMIN', 'ENGAGEMENT_ADMIN')
  AND permission.code = 'certification.inbound.ingest'
ON CONFLICT DO NOTHING;

CREATE OR REPLACE FUNCTION f04_user_principal_type_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.principal_type <> OLD.principal_type THEN
        RAISE EXCEPTION 'Identity principal type is immutable'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f04_user_principal_type_gate
BEFORE UPDATE ON user_profiles
FOR EACH ROW EXECUTE FUNCTION f04_user_principal_type_guard();

CREATE TABLE certification_rate_limit_buckets (
    actor_subject_hash VARCHAR(64) NOT NULL
        CHECK (actor_subject_hash ~ '^[0-9a-f]{64}$'),
    client_address_hash VARCHAR(64) NOT NULL
        CHECK (client_address_hash ~ '^[0-9a-f]{64}$'),
    operation VARCHAR(64) NOT NULL,
    bucket_start TIMESTAMPTZ NOT NULL,
    request_count INTEGER NOT NULL CHECK (request_count > 0),
    PRIMARY KEY (
        actor_subject_hash, client_address_hash, operation, bucket_start
    )
);

-- Evidence references retain whether the artifact satisfied a particular
-- frozen criterion.  The original outcome/clarification parent remains the
-- aggregate ownership boundary.
ALTER TABLE delivery_evidence_items
    ADD COLUMN criterion_id UUID REFERENCES delivery_acceptance_criteria(id);

CREATE UNIQUE INDEX uq_delivery_evidence_association
    ON delivery_evidence_items
        (outcome_id, clarification_id, criterion_id, artifact_id)
    NULLS NOT DISTINCT;

CREATE OR REPLACE FUNCTION enforce_f04_evidence_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    expected_engagement_id UUID;
    expected_month_id UUID;
    expected_deliverable_id UUID;
BEGIN
    IF NEW.outcome_id IS NOT NULL THEN
        SELECT month.engagement_id, month.id, outcome.deliverable_version_id
          INTO expected_engagement_id, expected_month_id, expected_deliverable_id
        FROM deliverable_delivery_outcomes outcome
        JOIN delivery_submissions submission ON submission.id = outcome.submission_id
        JOIN engagement_months month ON month.id = submission.engagement_month_id
        WHERE outcome.id = NEW.outcome_id;
    ELSE
        SELECT month.engagement_id, month.id, clarification.deliverable_version_id
          INTO expected_engagement_id, expected_month_id, expected_deliverable_id
        FROM certification_clarifications clarification
        JOIN delivery_submissions submission
          ON submission.id = clarification.submission_id
        JOIN engagement_months month ON month.id = submission.engagement_month_id
        WHERE clarification.id = NEW.clarification_id;
    END IF;

    IF expected_engagement_id IS NULL
       OR NOT EXISTS (
           SELECT 1
           FROM evidence_artifacts artifact
           WHERE artifact.id = NEW.artifact_id
             AND artifact.engagement_id = expected_engagement_id
             AND (
                 artifact.engagement_month_id IS NULL
                 OR artifact.engagement_month_id = expected_month_id
             )
       )
       OR (
           NEW.criterion_id IS NOT NULL
           AND NOT EXISTS (
               SELECT 1
               FROM delivery_acceptance_criteria criterion
               WHERE criterion.id = NEW.criterion_id
                 AND criterion.deliverable_version_id = expected_deliverable_id
           )
       ) THEN
        RAISE EXCEPTION 'Evidence reference is outside its certification scope'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f04_evidence_scope_gate
BEFORE INSERT OR UPDATE OF outcome_id, clarification_id, criterion_id, artifact_id
ON delivery_evidence_items
FOR EACH ROW EXECUTE FUNCTION enforce_f04_evidence_scope();

CREATE TABLE certification_evidence_exceptions (
    id UUID PRIMARY KEY,
    engagement_month_id UUID NOT NULL REFERENCES engagement_months(id),
    submission_id UUID NOT NULL REFERENCES delivery_submissions(id),
    deliverable_version_id UUID NOT NULL REFERENCES delivery_deliverable_versions(id),
    criterion_id UUID REFERENCES delivery_acceptance_criteria(id),
    reason_code VARCHAR(80) NOT NULL,
    justification TEXT NOT NULL,
    authority_snapshot JSONB NOT NULL,
    approved_by_subject VARCHAR(255) NOT NULL,
    approved_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    correlation_id UUID NOT NULL
);
CREATE UNIQUE INDEX uq_certification_evidence_exception_scope
    ON certification_evidence_exceptions
        (submission_id, deliverable_version_id, criterion_id)
    NULLS NOT DISTINCT;

CREATE OR REPLACE FUNCTION enforce_f04_evidence_exception_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM delivery_submissions submission
        JOIN deliverable_delivery_outcomes outcome
          ON outcome.submission_id = submission.id
         AND outcome.deliverable_version_id = NEW.deliverable_version_id
        WHERE submission.id = NEW.submission_id
          AND submission.engagement_month_id = NEW.engagement_month_id
    )
       OR (
           NEW.criterion_id IS NOT NULL
           AND NOT EXISTS (
               SELECT 1
               FROM delivery_acceptance_criteria criterion
               WHERE criterion.id = NEW.criterion_id
                 AND criterion.deliverable_version_id = NEW.deliverable_version_id
           )
       ) THEN
        RAISE EXCEPTION 'Evidence exception is outside its submission scope'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f04_evidence_exception_scope_gate
BEFORE INSERT ON certification_evidence_exceptions
FOR EACH ROW EXECUTE FUNCTION enforce_f04_evidence_exception_scope();
CREATE TRIGGER f04_evidence_exceptions_immutable
BEFORE UPDATE OR DELETE ON certification_evidence_exceptions
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

-- Monthly summaries must describe one internally consistent month/baseline.
CREATE OR REPLACE FUNCTION enforce_f04_summary_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM engagement_months month
        JOIN delivery_plans plan ON plan.engagement_month_id = month.id
        JOIN delivery_plan_versions version
          ON version.id = NEW.plan_version_id
         AND version.plan_id = plan.id
        JOIN delivery_plan_baselines baseline
          ON baseline.id = NEW.baseline_id
         AND baseline.plan_version_id = version.id
        JOIN delivery_submissions submission
          ON submission.id = NEW.submission_id
         AND submission.engagement_month_id = month.id
         AND submission.plan_version_id = version.id
         AND submission.baseline_id = baseline.id
         AND submission.policy_version_id = NEW.policy_version_id
        JOIN certification_rounds round
          ON round.id = NEW.round_id
         AND round.submission_id = submission.id
         AND round.engagement_month_id = month.id
         AND round.policy_version_id = NEW.policy_version_id
        JOIN certification_policy_versions policy
          ON policy.id = NEW.policy_version_id
         AND policy.engagement_id = month.engagement_id
        WHERE month.id = NEW.engagement_month_id
          AND version.state = 'FROZEN'
    ) THEN
        RAISE EXCEPTION 'Certification summary contains cross-scope references'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f04_summary_scope_gate
BEFORE INSERT OR UPDATE OF engagement_month_id, submission_id, round_id,
    plan_version_id, baseline_id, policy_version_id
ON monthly_certification_summaries
FOR EACH ROW EXECUTE FUNCTION enforce_f04_summary_scope();

CREATE OR REPLACE FUNCTION enforce_f04_confirmation_request_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM engagement_months month
        JOIN delivery_plans plan ON plan.engagement_month_id = month.id
        JOIN delivery_plan_versions version
          ON version.id = NEW.plan_version_id
         AND version.plan_id = plan.id
        JOIN delivery_plan_baselines baseline
          ON baseline.id = NEW.baseline_id
         AND baseline.plan_version_id = version.id
        JOIN monthly_certification_summaries summary
          ON summary.id = NEW.certification_summary_id
         AND summary.engagement_month_id = month.id
         AND summary.plan_version_id = version.id
         AND summary.baseline_id = baseline.id
         AND summary.policy_version_id = NEW.policy_version_id
         AND summary.status = 'CURRENT'
        JOIN certification_policy_versions policy
          ON policy.id = NEW.policy_version_id
         AND policy.engagement_id = month.engagement_id
        WHERE month.id = NEW.engagement_month_id
          AND (
              NEW.attendance_snapshot_id IS NULL
              OR EXISTS (
                  SELECT 1
                  FROM attendance_snapshot_versions attendance
                  WHERE attendance.id = NEW.attendance_snapshot_id
                    AND attendance.engagement_month_id = month.id
                    AND attendance.status = 'CLOSED'
              )
          )
          AND (
              NEW.supersedes_id IS NULL
              OR EXISTS (
                  SELECT 1
                  FROM business_confirmation_requests prior
                  WHERE prior.id = NEW.supersedes_id
                    AND prior.engagement_month_id = month.id
              )
          )
    ) THEN
        RAISE EXCEPTION 'Confirmation request contains cross-scope references'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f04_confirmation_request_scope_gate
BEFORE INSERT OR UPDATE OF engagement_month_id, attendance_snapshot_id,
    plan_version_id, baseline_id, certification_summary_id, policy_version_id,
    supersedes_id
ON business_confirmation_requests
FOR EACH ROW EXECUTE FUNCTION enforce_f04_confirmation_request_scope();

CREATE OR REPLACE FUNCTION enforce_f04_eligibility_snapshot_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM engagement_months month
        JOIN certification_policy_versions policy
          ON policy.id = NEW.policy_version_id
         AND policy.engagement_id = month.engagement_id
        WHERE month.id = NEW.engagement_month_id
          AND (
              NEW.project_id IS NULL
              OR EXISTS (
                  SELECT 1 FROM projects project
                  WHERE project.id = NEW.project_id
                    AND project.engagement_id = month.engagement_id
              )
          )
    ) THEN
        RAISE EXCEPTION 'Eligibility snapshot is outside its engagement scope'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f04_eligibility_snapshot_scope_gate
BEFORE INSERT OR UPDATE OF engagement_month_id, policy_version_id, project_id
ON confirmation_eligibility_snapshots
FOR EACH ROW EXECUTE FUNCTION enforce_f04_eligibility_snapshot_scope();

CREATE OR REPLACE FUNCTION enforce_f04_request_eligibility_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM business_confirmation_requests request
        JOIN confirmation_eligibility_snapshots eligibility
          ON eligibility.id = NEW.eligibility_id
        WHERE request.id = NEW.request_id
          AND eligibility.engagement_month_id = request.engagement_month_id
          AND eligibility.policy_version_id = request.policy_version_id
          AND eligibility.eligible_confirmer_subject =
              NEW.eligible_confirmer_subject
          AND eligibility.project_id IS NOT DISTINCT FROM NEW.project_id
          AND eligibility.sequence_number = NEW.sequence_number
    ) THEN
        RAISE EXCEPTION 'Request eligibility is outside the request scope'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f04_request_eligibility_scope_gate
BEFORE INSERT OR UPDATE ON confirmation_request_eligibility
FOR EACH ROW EXECUTE FUNCTION enforce_f04_request_eligibility_scope();
CREATE TRIGGER f04_request_eligibility_immutable
BEFORE UPDATE OR DELETE ON confirmation_request_eligibility
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

ALTER TABLE confirmation_secure_tokens
    ADD COLUMN project_id UUID REFERENCES projects(id);

DO $$
DECLARE
    found_constraint TEXT;
BEGIN
    SELECT constraint_name
      INTO found_constraint
    FROM information_schema.table_constraints
    WHERE table_schema = current_schema()
      AND table_name = 'confirmation_secure_tokens'
      AND constraint_type = 'UNIQUE'
      AND constraint_name <> 'confirmation_secure_tokens_pkey'
    ORDER BY constraint_name
    LIMIT 1;
    IF found_constraint IS NOT NULL THEN
        EXECUTE format(
            'ALTER TABLE confirmation_secure_tokens DROP CONSTRAINT %I',
            found_constraint
        );
    END IF;
END;
$$;

CREATE UNIQUE INDEX uq_confirmation_token_scope
    ON confirmation_secure_tokens
        (request_id, eligible_confirmer_subject, project_id)
    NULLS NOT DISTINCT;

CREATE TABLE confirmation_token_revocations (
    id UUID PRIMARY KEY,
    token_id UUID NOT NULL UNIQUE REFERENCES confirmation_secure_tokens(id),
    reason_code VARCHAR(80) NOT NULL,
    revoked_by_subject VARCHAR(255) NOT NULL,
    revoked_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TRIGGER f04_confirmation_token_revocations_immutable
BEFORE UPDATE OR DELETE ON confirmation_token_revocations
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

-- Plaintext action tokens are never written to notification bodies or database
-- columns.  A configured dispatcher receives them through this AES-GCM sealed,
-- single-outbox handoff and decrypts them only in worker memory.
CREATE TABLE confirmation_token_handoffs (
    id UUID PRIMARY KEY,
    token_id UUID NOT NULL UNIQUE REFERENCES confirmation_secure_tokens(id),
    request_id UUID NOT NULL REFERENCES business_confirmation_requests(id),
    outbox_id UUID NOT NULL UNIQUE REFERENCES notification_outbox(id),
    encrypted_token BYTEA NOT NULL,
    nonce BYTEA NOT NULL CHECK (octet_length(nonce) = 12),
    key_version VARCHAR(80) NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (
        status IN ('PENDING', 'DELIVERED', 'FAILED', 'REVOKED')
    ),
    failure_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delivered_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT ck_confirmation_token_handoff_state CHECK (
        (status = 'PENDING'
            AND delivered_at IS NULL AND revoked_at IS NULL)
        OR (status = 'DELIVERED'
            AND delivered_at IS NOT NULL AND revoked_at IS NULL)
        OR (status = 'FAILED'
            AND delivered_at IS NULL AND revoked_at IS NULL
            AND failure_code IS NOT NULL)
        OR (status = 'REVOKED'
            AND delivered_at IS NULL AND revoked_at IS NOT NULL
            AND failure_code IS NOT NULL)
    )
);

CREATE OR REPLACE FUNCTION enforce_f04_token_handoff_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM confirmation_secure_tokens token
        JOIN notification_outbox outbox
          ON outbox.id = NEW.outbox_id
         AND outbox.business_object_type = 'confirmation_secure_token'
         AND outbox.business_object_id = token.id
         AND outbox.engagement_month_id = (
             SELECT request.engagement_month_id
             FROM business_confirmation_requests request
             WHERE request.id = token.request_id
         )
        WHERE token.id = NEW.token_id
          AND token.request_id = NEW.request_id
    ) THEN
        RAISE EXCEPTION 'Secure token handoff is outside token/outbox scope'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f04_token_handoff_scope_gate
BEFORE INSERT ON confirmation_token_handoffs
FOR EACH ROW EXECUTE FUNCTION enforce_f04_token_handoff_scope();

CREATE OR REPLACE FUNCTION f04_token_handoff_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id <> OLD.id
       OR NEW.token_id <> OLD.token_id
       OR NEW.request_id <> OLD.request_id
       OR NEW.outbox_id <> OLD.outbox_id
       OR NEW.encrypted_token <> OLD.encrypted_token
       OR NEW.nonce <> OLD.nonce
       OR NEW.key_version <> OLD.key_version
       OR NEW.created_at <> OLD.created_at
       OR NOT (
           (OLD.status = 'PENDING'
               AND NEW.status IN ('DELIVERED', 'FAILED', 'REVOKED'))
           OR (OLD.status = 'FAILED'
               AND NEW.status = 'PENDING'
               AND NEW.failure_code IS NULL
               AND NEW.delivered_at IS NULL
               AND NEW.revoked_at IS NULL)
       ) THEN
        RAISE EXCEPTION 'Secure token handoff content is immutable'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f04_token_handoff_update_gate
BEFORE UPDATE ON confirmation_token_handoffs
FOR EACH ROW EXECUTE FUNCTION f04_token_handoff_guard();
CREATE TRIGGER f04_token_handoff_delete_gate
BEFORE DELETE ON confirmation_token_handoffs
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

CREATE OR REPLACE FUNCTION enforce_f04_secure_token_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.project_id IS NULL
       AND (
           SELECT COUNT(*)
           FROM confirmation_request_eligibility eligibility
           WHERE eligibility.request_id = NEW.request_id
             AND eligibility.eligible_confirmer_subject =
                 NEW.eligible_confirmer_subject
       ) = 1 THEN
        SELECT eligibility.project_id
          INTO NEW.project_id
        FROM confirmation_request_eligibility eligibility
        WHERE eligibility.request_id = NEW.request_id
          AND eligibility.eligible_confirmer_subject =
              NEW.eligible_confirmer_subject
        ORDER BY eligibility.sequence_number
        LIMIT 1;
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM business_confirmation_requests request
        JOIN confirmation_request_eligibility eligibility
          ON eligibility.request_id = request.id
         AND eligibility.eligible_confirmer_subject =
             NEW.eligible_confirmer_subject
         AND eligibility.project_id IS NOT DISTINCT FROM NEW.project_id
        WHERE request.id = NEW.request_id
          AND request.version = NEW.request_version
          AND NEW.expires_at <= request.due_at
          AND NEW.expires_at > NEW.created_at
    ) THEN
        RAISE EXCEPTION 'Secure token is outside eligibility, version, or due scope'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f04_secure_token_scope_gate
BEFORE INSERT OR UPDATE OF request_id, request_version,
    eligible_confirmer_subject, project_id, expires_at, created_at
ON confirmation_secure_tokens
FOR EACH ROW EXECUTE FUNCTION enforce_f04_secure_token_scope();

CREATE OR REPLACE FUNCTION enforce_f04_confirmation_action_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM business_confirmation_requests request
        JOIN confirmation_request_eligibility eligibility
          ON eligibility.request_id = request.id
         AND eligibility.eligible_confirmer_subject = NEW.actor_subject
         AND eligibility.project_id IS NOT DISTINCT FROM NEW.project_id
        WHERE request.id = NEW.request_id
          AND request.version = NEW.request_version
          AND (
              NEW.token_id IS NULL
              OR EXISTS (
                  SELECT 1
                  FROM confirmation_secure_tokens token
                  WHERE token.id = NEW.token_id
                    AND token.request_id = request.id
                    AND token.request_version = request.version
                    AND token.eligible_confirmer_subject = NEW.actor_subject
                    AND token.project_id IS NOT DISTINCT FROM NEW.project_id
              )
          )
    ) THEN
        RAISE EXCEPTION 'Confirmation action is outside actor project scope'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f04_confirmation_action_scope_gate
BEFORE INSERT OR UPDATE OF request_id, request_version, token_id,
    actor_subject, project_id
ON business_confirmation_actions
FOR EACH ROW EXECUTE FUNCTION enforce_f04_confirmation_action_scope();

-- Permit explicit conflict review without falsely presenting the request as
-- terminal.  Conflict resolution is recorded as a separate immutable decision.
ALTER TABLE business_confirmation_requests
    ADD COLUMN due_offset_seconds INTEGER NOT NULL DEFAULT 0
        CHECK (due_offset_seconds BETWEEN -64800 AND 64800);

ALTER TABLE business_confirmation_requests
    DROP CONSTRAINT business_confirmation_requests_status_check;
ALTER TABLE business_confirmation_requests
    ADD CONSTRAINT ck_business_confirmation_request_status_v12 CHECK (
        status IN (
            'DRAFT', 'QUEUED', 'SENT', 'AWAITING_RESPONSE',
            'CONFLICT_REVIEW', 'CONFIRMED', 'CHANGES_REQUESTED', 'REJECTED',
            'EXPIRED', 'CANCELLED', 'SUPERSEDED'
        )
    );
ALTER TABLE business_confirmation_requests
    DROP CONSTRAINT ck_confirmation_completed;
ALTER TABLE business_confirmation_requests
    ADD CONSTRAINT ck_confirmation_completed_v12 CHECK (
        (status IN ('CONFIRMED', 'CHANGES_REQUESTED', 'REJECTED')
            AND completed_at IS NOT NULL)
        OR (status IN (
                'DRAFT', 'QUEUED', 'SENT', 'AWAITING_RESPONSE',
                'CONFLICT_REVIEW', 'EXPIRED', 'CANCELLED'
            )
            AND completed_at IS NULL)
        -- Supersession preserves the original terminal timestamp when one
        -- exists, while an unfinished superseded request remains undated.
        OR status = 'SUPERSEDED'
    );

CREATE TABLE business_confirmation_governance_decisions (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES business_confirmation_requests(id),
    request_version INTEGER NOT NULL CHECK (request_version > 0),
    decision VARCHAR(32) NOT NULL CHECK (
        decision IN ('CONFIRM', 'REQUEST_CORRECTION', 'REJECT')
    ),
    reasoning TEXT NOT NULL,
    action_ids JSONB NOT NULL,
    authority_snapshot JSONB NOT NULL,
    decided_by_subject VARCHAR(255) NOT NULL,
    decided_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    correlation_id UUID NOT NULL,
    UNIQUE (request_id, request_version)
);

CREATE OR REPLACE FUNCTION enforce_f04_governance_decision_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF jsonb_typeof(NEW.action_ids) <> 'array'
       OR jsonb_array_length(NEW.action_ids) = 0
       OR NOT EXISTS (
        SELECT 1
        FROM business_confirmation_requests request
        WHERE request.id = NEW.request_id
          AND request.version = NEW.request_version
          AND request.status = 'CONFLICT_REVIEW'
    ) THEN
        RAISE EXCEPTION 'Governance decision requires matching conflict review'
            USING ERRCODE = '23514';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM jsonb_array_elements_text(NEW.action_ids) submitted(action_id)
        WHERE NOT EXISTS (
            SELECT 1
            FROM business_confirmation_actions action
            WHERE action.id = submitted.action_id::uuid
              AND action.request_id = NEW.request_id
        )
    )
       OR (
           SELECT COUNT(DISTINCT submitted.action_id)
           FROM jsonb_array_elements_text(NEW.action_ids)
               submitted(action_id)
       ) <> (
           SELECT COUNT(*)
           FROM business_confirmation_actions action
           WHERE action.request_id = NEW.request_id
       )
       OR EXISTS (
           SELECT 1
           FROM business_confirmation_actions action
           WHERE action.request_id = NEW.request_id
             AND action.actor_subject = NEW.decided_by_subject
       ) THEN
        RAISE EXCEPTION 'Governance decision action scope or duties are invalid'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f04_governance_decision_scope_gate
BEFORE INSERT ON business_confirmation_governance_decisions
FOR EACH ROW EXECUTE FUNCTION enforce_f04_governance_decision_scope();
CREATE TRIGGER f04_governance_decisions_immutable
BEFORE UPDATE OR DELETE ON business_confirmation_governance_decisions
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

-- V11 invalidation facts remain immutable.  Effective resolution is a distinct
-- append-only fact and cannot erase the reason or lineage of the invalidation.
CREATE TABLE certification_invalidation_resolutions (
    id UUID PRIMARY KEY,
    invalidation_id UUID NOT NULL UNIQUE REFERENCES certification_invalidations(id),
    resolution VARCHAR(24) NOT NULL CHECK (
        resolution IN ('CLEARED', 'SUPERSEDED')
    ),
    reasoning TEXT NOT NULL,
    evidence_manifest JSONB NOT NULL DEFAULT '{}'::jsonb,
    resolved_by_subject VARCHAR(255) NOT NULL,
    resolved_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    correlation_id UUID NOT NULL
);
CREATE TRIGGER f04_invalidation_resolutions_immutable
BEFORE UPDATE OR DELETE ON certification_invalidation_resolutions
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

CREATE VIEW effective_certification_invalidations AS
SELECT invalidation.id,
       invalidation.engagement_month_id,
       invalidation.reopen_request_id,
       invalidation.object_type,
       invalidation.object_id,
       invalidation.reason_code,
       invalidation.downstream_contract,
       CASE
           WHEN resolution.id IS NULL THEN invalidation.status
           ELSE resolution.resolution
       END AS effective_status,
       invalidation.correlation_id,
       invalidation.created_at,
       invalidation.created_by_subject,
       resolution.id AS resolution_id,
       resolution.reasoning AS resolution_reasoning,
       resolution.resolved_at,
       resolution.resolved_by_subject
FROM certification_invalidations invalidation
LEFT JOIN certification_invalidation_resolutions resolution
  ON resolution.invalidation_id = invalidation.id;

CREATE TABLE certification_attendance_exceptions (
    id UUID PRIMARY KEY,
    engagement_month_id UUID NOT NULL REFERENCES engagement_months(id),
    policy_version_id UUID NOT NULL REFERENCES certification_policy_versions(id),
    reason_code VARCHAR(80) NOT NULL,
    justification TEXT NOT NULL,
    disclosure_manifest JSONB NOT NULL,
    authority_snapshot JSONB NOT NULL,
    approved_by_subject VARCHAR(255) NOT NULL,
    approved_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    correlation_id UUID NOT NULL,
    UNIQUE (engagement_month_id, policy_version_id)
);

CREATE OR REPLACE FUNCTION enforce_f04_attendance_exception_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM engagement_months month
        JOIN certification_policy_versions policy
          ON policy.id = NEW.policy_version_id
         AND policy.engagement_id = month.engagement_id
        WHERE month.id = NEW.engagement_month_id
    ) THEN
        RAISE EXCEPTION 'Attendance exception is outside policy scope'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f04_attendance_exception_scope_gate
BEFORE INSERT ON certification_attendance_exceptions
FOR EACH ROW EXECUTE FUNCTION enforce_f04_attendance_exception_scope();
CREATE TRIGGER f04_attendance_exceptions_immutable
BEFORE UPDATE OR DELETE ON certification_attendance_exceptions
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

CREATE OR REPLACE FUNCTION enforce_f04_month_closure_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF jsonb_typeof(NEW.manifest) <> 'object'
       OR NEW.manifest->>'schema' <> 'f04-month-closure-v1'
       OR NOT NEW.manifest ?& ARRAY[
           'monthId', 'monthVersion', 'policyVersionId',
           'attendanceSnapshotId', 'attendanceExceptionId',
           'planVersionId', 'baselineId', 'baselineChecksum',
           'submissionId', 'submissionChecksum',
           'summaryId', 'summaryVersion', 'summaryChecksum',
           'confirmationRequestId', 'confirmationVersion',
           'confirmationScopeChecksum', 'readinessRunId',
           'readinessInputHash', 'activeInvalidationCount',
           'f05HandoffId', 'f05PackageHash'
       ]
       OR NEW.manifest->>'monthId' <> NEW.engagement_month_id::text
       OR NEW.manifest->>'confirmationRequestId' <>
          NEW.confirmation_request_id::text
       OR COALESCE((NEW.manifest->>'activeInvalidationCount')::integer, -1) <> 0
       OR NOT EXISTS (
        SELECT 1
        FROM business_confirmation_requests request
        WHERE request.id = NEW.confirmation_request_id
          AND request.engagement_month_id = NEW.engagement_month_id
          AND request.status = 'CONFIRMED'
    )
       OR (
           NEW.supersedes_id IS NOT NULL
           AND NOT EXISTS (
               SELECT 1
               FROM month_closures prior
               WHERE prior.id = NEW.supersedes_id
                 AND prior.engagement_month_id = NEW.engagement_month_id
           )
       ) THEN
        RAISE EXCEPTION 'Month closure requires a confirmed in-scope request'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f04_month_closure_scope_gate
BEFORE INSERT ON month_closures
FOR EACH ROW EXECUTE FUNCTION enforce_f04_month_closure_scope();

CREATE OR REPLACE FUNCTION f04_month_closure_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status <> 'CURRENT'
       OR NEW.status <> 'SUPERSEDED'
       OR NEW.id <> OLD.id
       OR NEW.engagement_month_id <> OLD.engagement_month_id
       OR NEW.version <> OLD.version
       OR NEW.confirmation_request_id <> OLD.confirmation_request_id
       OR NEW.manifest <> OLD.manifest
       OR NEW.manifest_hash <> OLD.manifest_hash
       OR NEW.hash_schema_version <> OLD.hash_schema_version
       OR NEW.supersedes_id IS DISTINCT FROM OLD.supersedes_id
       OR NEW.closed_at <> OLD.closed_at
       OR NEW.closed_by_subject <> OLD.closed_by_subject THEN
        RAISE EXCEPTION 'Month closure content is immutable'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f04_month_closure_update_gate
BEFORE UPDATE ON month_closures
FOR EACH ROW EXECUTE FUNCTION f04_month_closure_guard();
CREATE TRIGGER f04_month_closure_delete_gate
BEFORE DELETE ON month_closures
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

CREATE OR REPLACE FUNCTION enforce_f04_reopen_request_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.closure_id IS NOT NULL
       AND NOT EXISTS (
           SELECT 1
           FROM month_closures closure
           WHERE closure.id = NEW.closure_id
             AND closure.engagement_month_id = NEW.engagement_month_id
       ) THEN
        RAISE EXCEPTION 'Reopen request closure is outside month scope'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f04_reopen_request_scope_gate
BEFORE INSERT ON month_reopen_requests
FOR EACH ROW EXECUTE FUNCTION enforce_f04_reopen_request_scope();
CREATE TRIGGER f04_reopen_requests_immutable
BEFORE UPDATE OR DELETE ON month_reopen_requests
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

CREATE TABLE month_reopen_decisions (
    id UUID PRIMARY KEY,
    reopen_request_id UUID NOT NULL UNIQUE REFERENCES month_reopen_requests(id),
    decision VARCHAR(16) NOT NULL CHECK (decision IN ('APPROVE', 'REJECT')),
    reasoning TEXT NOT NULL,
    authority_snapshot JSONB NOT NULL,
    decided_by_subject VARCHAR(255) NOT NULL,
    decided_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    correlation_id UUID NOT NULL
);
CREATE TRIGGER f04_reopen_decisions_immutable
BEFORE UPDATE OR DELETE ON month_reopen_decisions
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

CREATE VIEW effective_month_reopen_requests AS
SELECT request.*,
       CASE
           WHEN decision.decision = 'APPROVE' THEN 'APPROVED'
           WHEN decision.decision = 'REJECT' THEN 'REJECTED'
           ELSE request.status
       END AS effective_status,
       decision.id AS decision_id,
       decision.reasoning AS effective_decision_reason,
       decision.decided_at AS effective_decided_at,
       decision.decided_by_subject AS effective_decided_by_subject
FROM month_reopen_requests request
LEFT JOIN month_reopen_decisions decision
  ON decision.reopen_request_id = request.id;

-- Durable, local handoff facts expose what was handed off without asserting
-- that an external F05 consumer accepted or processed it.
CREATE TABLE f05_certification_handoffs (
    id UUID PRIMARY KEY,
    engagement_month_id UUID NOT NULL REFERENCES engagement_months(id),
    confirmation_request_id UUID NOT NULL REFERENCES business_confirmation_requests(id),
    closure_id UUID REFERENCES month_closures(id),
    readiness_run_id UUID NOT NULL REFERENCES certification_readiness_runs(id),
    package_manifest JSONB NOT NULL,
    package_hash VARCHAR(64) NOT NULL CHECK (package_hash ~ '^[0-9a-f]{64}$'),
    status VARCHAR(24) NOT NULL CHECK (
        status IN ('READY_LOCAL', 'PUBLISHED', 'REJECTED', 'INVALIDATED')
    ),
    downstream_reference VARCHAR(255),
    created_by_subject VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    correlation_id UUID NOT NULL,
    UNIQUE (confirmation_request_id, package_hash)
);

CREATE OR REPLACE FUNCTION enforce_f04_f05_handoff_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM business_confirmation_requests request
        JOIN certification_readiness_runs readiness
          ON readiness.id = NEW.readiness_run_id
         AND readiness.engagement_month_id = request.engagement_month_id
        WHERE request.id = NEW.confirmation_request_id
          AND request.engagement_month_id = NEW.engagement_month_id
          AND request.status = 'CONFIRMED'
          AND readiness.ready_for_f05_handoff
          AND (
              NEW.closure_id IS NULL
              OR EXISTS (
                  SELECT 1
                  FROM month_closures closure
                  WHERE closure.id = NEW.closure_id
                    AND closure.engagement_month_id = request.engagement_month_id
              )
          )
    ) THEN
        RAISE EXCEPTION 'F05 handoff is outside confirmed readiness scope'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f04_f05_handoff_scope_gate
BEFORE INSERT ON f05_certification_handoffs
FOR EACH ROW EXECUTE FUNCTION enforce_f04_f05_handoff_scope();
CREATE TRIGGER f04_f05_handoffs_immutable
BEFORE UPDATE OR DELETE ON f05_certification_handoffs
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

CREATE TABLE f05_handoff_invalidations (
    id UUID PRIMARY KEY,
    handoff_id UUID NOT NULL REFERENCES f05_certification_handoffs(id),
    reopen_decision_id UUID NOT NULL REFERENCES month_reopen_decisions(id),
    reason_code VARCHAR(80) NOT NULL,
    invalidated_by_subject VARCHAR(255) NOT NULL,
    correlation_id UUID NOT NULL,
    invalidated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (handoff_id, reopen_decision_id)
);

CREATE OR REPLACE FUNCTION enforce_f04_handoff_invalidation_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM f05_certification_handoffs handoff
        JOIN month_reopen_decisions decision
          ON decision.id = NEW.reopen_decision_id
         AND decision.decision = 'APPROVE'
        JOIN month_reopen_requests request
          ON request.id = decision.reopen_request_id
         AND request.engagement_month_id = handoff.engagement_month_id
        WHERE handoff.id = NEW.handoff_id
          AND decision.decided_by_subject = NEW.invalidated_by_subject
    ) THEN
        RAISE EXCEPTION 'F05 handoff invalidation is outside approved reopen scope'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f04_handoff_invalidation_scope_gate
BEFORE INSERT ON f05_handoff_invalidations
FOR EACH ROW EXECUTE FUNCTION enforce_f04_handoff_invalidation_scope();
CREATE TRIGGER f04_handoff_invalidations_immutable
BEFORE UPDATE OR DELETE ON f05_handoff_invalidations
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

CREATE VIEW effective_f05_certification_handoffs AS
SELECT handoff.*,
       CASE
           WHEN EXISTS (
               SELECT 1
               FROM f05_handoff_invalidations invalidation
               WHERE invalidation.handoff_id = handoff.id
           ) THEN 'INVALIDATED'
           ELSE handoff.status
       END AS effective_status
FROM f05_certification_handoffs handoff;

CREATE TABLE f05_handoff_publish_attempts (
    id UUID PRIMARY KEY,
    handoff_id UUID NOT NULL REFERENCES f05_certification_handoffs(id),
    status VARCHAR(32) NOT NULL,
    contract_version VARCHAR(80) NOT NULL,
    sanitized_failure_code VARCHAR(80),
    correlation_id UUID NOT NULL,
    attempted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TRIGGER f04_f05_handoff_attempts_immutable
BEFORE UPDATE OR DELETE ON f05_handoff_publish_attempts
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

CREATE TABLE f05_handoff_publish_jobs (
    id UUID PRIMARY KEY,
    handoff_id UUID NOT NULL UNIQUE REFERENCES f05_certification_handoffs(id),
    status VARCHAR(24) NOT NULL CHECK (
        status IN ('PENDING', 'CLAIMED', 'COMPLETED', 'DEAD_LETTER')
    ),
    lease_owner VARCHAR(160),
    lease_expires_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    last_error_code VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_f05_handoff_publish_job_lease CHECK (
        (lease_owner IS NULL AND lease_expires_at IS NULL)
        OR (lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)
    )
);
CREATE INDEX idx_f05_handoff_publish_jobs_due
    ON f05_handoff_publish_jobs(next_attempt_at, created_at)
    WHERE status IN ('PENDING', 'CLAIMED');

CREATE OR REPLACE VIEW effective_f05_certification_handoffs AS
SELECT handoff.*,
       CASE
           WHEN EXISTS (
               SELECT 1
               FROM f05_handoff_invalidations invalidation
               WHERE invalidation.handoff_id = handoff.id
           ) THEN 'INVALIDATED'
           WHEN EXISTS (
               SELECT 1
               FROM f05_handoff_publish_attempts attempt
               WHERE attempt.handoff_id = handoff.id
                 AND attempt.status IN ('PUBLISHED', 'ACCEPTED', 'SENT')
           ) THEN 'PUBLISHED'
           ELSE handoff.status
       END AS effective_status
FROM f05_certification_handoffs handoff;

-- Persist local scheduling, leasing and dead-letter state for provider-neutral
-- delivery workers.  Content remains protected by V11's outbox guard.
ALTER TABLE notification_outbox
    ADD COLUMN lease_owner VARCHAR(160),
    ADD COLUMN lease_expires_at TIMESTAMPTZ,
    ADD COLUMN dead_lettered_at TIMESTAMPTZ,
    ADD COLUMN last_error_code VARCHAR(64),
    ADD COLUMN replay_count INTEGER NOT NULL DEFAULT 0
        CHECK (replay_count >= 0),
    ADD COLUMN generation_attempt_count INTEGER NOT NULL DEFAULT 0
        CHECK (generation_attempt_count >= 0),
    ADD CONSTRAINT ck_notification_outbox_lease CHECK (
        (lease_owner IS NULL AND lease_expires_at IS NULL)
        OR (lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)
    ),
    ADD CONSTRAINT ck_notification_outbox_dead_letter CHECK (
        (transport_status = 'DEAD_LETTER' AND dead_lettered_at IS NOT NULL)
        OR (transport_status <> 'DEAD_LETTER' AND dead_lettered_at IS NULL)
    );

CREATE TABLE notification_outbox_replays (
    id UUID PRIMARY KEY,
    outbox_id UUID NOT NULL REFERENCES notification_outbox(id),
    replay_number INTEGER NOT NULL CHECK (replay_number > 0),
    reason TEXT NOT NULL,
    replayed_by_subject VARCHAR(255) NOT NULL,
    correlation_id UUID NOT NULL,
    replayed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (outbox_id, replay_number)
);
CREATE TRIGGER f04_notification_outbox_replays_immutable
BEFORE UPDATE OR DELETE ON notification_outbox_replays
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

CREATE INDEX idx_notification_outbox_dispatch
    ON notification_outbox(next_attempt_at, created_at)
    WHERE transport_status IN ('QUEUED', 'FAILED');

CREATE TABLE confirmation_request_schedules (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES business_confirmation_requests(id),
    schedule_type VARCHAR(24) NOT NULL CHECK (
        schedule_type IN ('REMINDER', 'EXPIRY')
    ),
    sequence_number INTEGER NOT NULL CHECK (sequence_number > 0),
    due_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (
        status IN ('PENDING', 'CLAIMED', 'COMPLETED', 'CANCELLED', 'DEAD_LETTER')
    ),
    lease_owner VARCHAR(160),
    lease_expires_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    last_error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (request_id, schedule_type, sequence_number),
    CONSTRAINT ck_confirmation_schedule_lease CHECK (
        (lease_owner IS NULL AND lease_expires_at IS NULL)
        OR (lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)
    )
);
CREATE INDEX idx_confirmation_request_schedules_due
    ON confirmation_request_schedules(next_attempt_at, due_at)
    WHERE status IN ('PENDING', 'CLAIMED');

-- Policy versions are immutable except for the one-way ACTIVE -> SUPERSEDED
-- lifecycle transition needed to introduce a new version.
DROP TRIGGER f04_certification_policy_immutable
    ON certification_policy_versions;

CREATE OR REPLACE FUNCTION f04_certification_policy_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status <> 'ACTIVE'
       OR NEW.status <> 'SUPERSEDED'
       OR NEW.id <> OLD.id
       OR NEW.engagement_id <> OLD.engagement_id
       OR NEW.version <> OLD.version
       OR NEW.attendance_required <> OLD.attendance_required
       OR NEW.separation_of_duties_required <>
          OLD.separation_of_duties_required
       OR NEW.monthly_decision_required <> OLD.monthly_decision_required
       OR NEW.manual_second_review_required <>
          OLD.manual_second_review_required
       OR NEW.deemed_submission_approval_enabled <>
          OLD.deemed_submission_approval_enabled
       OR NEW.deemed_certification_approval_enabled <>
          OLD.deemed_certification_approval_enabled
       OR NEW.deemed_confirmation_approval_enabled <>
          OLD.deemed_confirmation_approval_enabled
       OR NEW.quorum_mode <> OLD.quorum_mode
       OR NEW.quorum_required <> OLD.quorum_required
       OR NEW.token_ttl_seconds <> OLD.token_ttl_seconds
       OR NEW.confirmation_due_seconds <> OLD.confirmation_due_seconds
       OR NEW.reminder_policy <> OLD.reminder_policy
       OR NEW.evidence_policy <> OLD.evidence_policy
       OR NEW.recipient_policy <> OLD.recipient_policy
       OR NEW.retention_policy <> OLD.retention_policy
       OR NEW.policy_hash <> OLD.policy_hash
       OR NEW.hash_algorithm <> OLD.hash_algorithm
       OR NEW.hash_schema_version <> OLD.hash_schema_version
       OR NEW.created_at <> OLD.created_at
       OR NEW.created_by_subject <> OLD.created_by_subject THEN
        RAISE EXCEPTION 'Certification policy content is immutable'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f04_certification_policy_update_gate
BEFORE UPDATE ON certification_policy_versions
FOR EACH ROW EXECUTE FUNCTION f04_certification_policy_guard();
CREATE TRIGGER f04_certification_policy_delete_gate
BEFORE DELETE ON certification_policy_versions
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

-- Restricted inbound/manual review is append-only and scope-safe.  A single
-- authoritative review fact resolves an item; retries are handled by the
-- generic F04 idempotency ledger rather than by adding duplicate reviewers.
CREATE UNIQUE INDEX uq_inbound_confirmation_review
    ON inbound_confirmation_reviews(inbound_message_id);

CREATE OR REPLACE FUNCTION enforce_f04_inbound_message_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.request_id IS NOT NULL AND (
        NEW.engagement_month_id IS NULL
        OR NOT EXISTS (
            SELECT 1
            FROM business_confirmation_requests request
            WHERE request.id = NEW.request_id
              AND request.engagement_month_id = NEW.engagement_month_id
        )
    ) THEN
        RAISE EXCEPTION 'Inbound confirmation message is outside request scope'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f04_inbound_message_scope_gate
BEFORE INSERT ON inbound_confirmation_messages
FOR EACH ROW EXECUTE FUNCTION enforce_f04_inbound_message_scope();

CREATE OR REPLACE FUNCTION enforce_f04_manual_evidence_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM engagement_months month
        JOIN evidence_artifacts artifact
          ON artifact.id = NEW.artifact_id
         AND artifact.engagement_month_id = month.id
         AND artifact.engagement_id = month.engagement_id
         AND artifact.scan_status IN ('PASSED', 'NOT_REQUIRED')
        WHERE month.id = NEW.engagement_month_id
          AND (
              NEW.request_id IS NULL
              OR EXISTS (
                  SELECT 1
                  FROM business_confirmation_requests request
                  WHERE request.id = NEW.request_id
                    AND request.engagement_month_id = month.id
              )
          )
    ) THEN
        RAISE EXCEPTION 'Manual confirmation evidence is outside month/request scope'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f04_manual_evidence_scope_gate
BEFORE INSERT ON manual_confirmation_evidence
FOR EACH ROW EXECUTE FUNCTION enforce_f04_manual_evidence_scope();
CREATE TRIGGER f04_manual_evidence_immutable
BEFORE UPDATE OR DELETE ON manual_confirmation_evidence
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

-- Tighten request immutability, transport transitions and conflict governance.
CREATE OR REPLACE FUNCTION f04_confirmation_transition_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    transition_allowed BOOLEAN;
    transport_allowed BOOLEAN;
BEGIN
    transition_allowed := CASE OLD.status
        WHEN 'DRAFT' THEN NEW.status IN ('DRAFT', 'QUEUED', 'CANCELLED')
        WHEN 'QUEUED' THEN NEW.status IN (
            'QUEUED', 'SENT', 'AWAITING_RESPONSE', 'CANCELLED', 'SUPERSEDED'
        )
        WHEN 'SENT' THEN NEW.status IN (
            'SENT', 'AWAITING_RESPONSE', 'CANCELLED', 'SUPERSEDED'
        )
        WHEN 'AWAITING_RESPONSE' THEN NEW.status IN (
            'AWAITING_RESPONSE', 'CONFLICT_REVIEW', 'CONFIRMED',
            'CHANGES_REQUESTED', 'REJECTED', 'EXPIRED', 'CANCELLED',
            'SUPERSEDED'
        )
        WHEN 'CONFLICT_REVIEW' THEN NEW.status IN (
            'CONFLICT_REVIEW', 'CONFIRMED', 'CHANGES_REQUESTED', 'REJECTED',
            'EXPIRED', 'CANCELLED', 'SUPERSEDED'
        )
        WHEN 'CONFIRMED' THEN NEW.status IN ('CONFIRMED', 'SUPERSEDED')
        WHEN 'CHANGES_REQUESTED' THEN
            NEW.status IN ('CHANGES_REQUESTED', 'SUPERSEDED')
        WHEN 'REJECTED' THEN NEW.status IN ('REJECTED', 'SUPERSEDED')
        ELSE NEW.status = OLD.status
    END;

    transport_allowed := CASE OLD.transport_status
        WHEN 'NOT_CONFIGURED' THEN NEW.transport_status IN (
            'NOT_CONFIGURED', 'QUEUED'
        )
        WHEN 'QUEUED' THEN NEW.transport_status IN (
            'QUEUED', 'SENT', 'FAILED'
        )
        WHEN 'SENT' THEN NEW.transport_status IN (
            'SENT', 'DELIVERED', 'BOUNCED', 'FAILED'
        )
        WHEN 'BOUNCED' THEN NEW.transport_status IN (
            'BOUNCED', 'QUEUED', 'FAILED'
        )
        WHEN 'FAILED' THEN NEW.transport_status IN ('FAILED', 'QUEUED')
        ELSE NEW.transport_status = OLD.transport_status
    END;

    IF NOT transition_allowed
       OR NOT transport_allowed
       OR NEW.id <> OLD.id
       OR NEW.engagement_month_id <> OLD.engagement_month_id
       OR NEW.attendance_snapshot_id IS DISTINCT FROM OLD.attendance_snapshot_id
       OR NEW.plan_version_id <> OLD.plan_version_id
       OR NEW.baseline_id <> OLD.baseline_id
       OR NEW.certification_summary_id <> OLD.certification_summary_id
       OR NEW.policy_version_id <> OLD.policy_version_id
       OR NEW.package_version_reference IS DISTINCT FROM
          OLD.package_version_reference
       OR NEW.version <> OLD.version
       OR NEW.supersedes_id IS DISTINCT FROM OLD.supersedes_id
       OR NEW.quorum_mode <> OLD.quorum_mode
       OR NEW.quorum_required <> OLD.quorum_required
       OR NEW.recipient_snapshot <> OLD.recipient_snapshot
       OR NEW.eligibility_snapshot <> OLD.eligibility_snapshot
       OR NEW.scope_manifest <> OLD.scope_manifest
       OR NEW.scope_checksum <> OLD.scope_checksum
       OR NEW.hash_algorithm <> OLD.hash_algorithm
       OR NEW.hash_schema_version <> OLD.hash_schema_version
       OR NEW.requested_at <> OLD.requested_at
       OR NEW.due_at <> OLD.due_at
       OR NEW.due_offset_seconds <> OLD.due_offset_seconds
       OR NEW.created_by_subject <> OLD.created_by_subject
       OR NEW.optimistic_version <> OLD.optimistic_version + 1 THEN
        RAISE EXCEPTION 'Invalid confirmation request transition'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

-- Include the project binding in the secure-token transition guard.
CREATE OR REPLACE FUNCTION f04_secure_token_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.consumed_at IS NOT NULL
       OR NEW.id <> OLD.id
       OR NEW.request_id <> OLD.request_id
       OR NEW.request_version <> OLD.request_version
       OR NEW.eligible_confirmer_subject <> OLD.eligible_confirmer_subject
       OR NEW.project_id IS DISTINCT FROM OLD.project_id
       OR NEW.token_hash <> OLD.token_hash
       OR NEW.token_salt <> OLD.token_salt
       OR NEW.hash_algorithm <> OLD.hash_algorithm
       OR NEW.work_factor <> OLD.work_factor
       OR NEW.expires_at <> OLD.expires_at
       OR NEW.created_at <> OLD.created_at
       OR NEW.consumed_at IS NULL
       OR NEW.consumed_by_subject IS NULL
       OR NEW.consumed_by_subject <> NEW.eligible_confirmer_subject
       OR NEW.consumed_at > NEW.expires_at THEN
        RAISE EXCEPTION 'Invalid secure token mutation'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;
