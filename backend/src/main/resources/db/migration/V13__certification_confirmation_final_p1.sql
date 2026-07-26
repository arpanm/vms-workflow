-- Final local F04 P1 integrity fences.
--
-- This migration remains provider-neutral. It makes correction, reviewed
-- evidence promotion, and F05 invalidation facts locally authoritative without
-- claiming that an external mailbox or F05 consumer is deployed.

-- A summary predecessor is part of the same immutable lineage as every other
-- summary source and therefore must belong to the same engagement month.
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
          AND (
              NEW.supersedes_id IS NULL
              OR EXISTS (
                  SELECT 1
                  FROM monthly_certification_summaries prior
                  WHERE prior.id = NEW.supersedes_id
                    AND prior.engagement_month_id = month.id
              )
          )
    ) THEN
        RAISE EXCEPTION 'Certification summary contains cross-scope references'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER f04_summary_scope_gate ON monthly_certification_summaries;
CREATE TRIGGER f04_summary_scope_gate
BEFORE INSERT OR UPDATE OF engagement_month_id, submission_id, round_id,
    plan_version_id, baseline_id, policy_version_id, supersedes_id
ON monthly_certification_summaries
FOR EACH ROW EXECUTE FUNCTION enforce_f04_summary_scope();

-- Reopen impacts are a closed typed set.  This function is also used by the
-- resolution gate so object type cannot be relabelled to escape exact lineage.
CREATE OR REPLACE FUNCTION f04_fact_month(
    fact_type VARCHAR,
    fact_id UUID
)
RETURNS UUID
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
    value UUID;
BEGIN
    CASE fact_type
        WHEN 'ATTENDANCE_SNAPSHOT_VERSION' THEN
            SELECT engagement_month_id INTO value
            FROM attendance_snapshot_versions WHERE id = fact_id;
        WHEN 'DELIVERY_PLAN_VERSION' THEN
            SELECT plan.engagement_month_id INTO value
            FROM delivery_plan_versions version
            JOIN delivery_plans plan ON plan.id = version.plan_id
            WHERE version.id = fact_id;
        WHEN 'DELIVERY_PLAN_BASELINE' THEN
            SELECT plan.engagement_month_id INTO value
            FROM delivery_plan_baselines baseline
            JOIN delivery_plan_versions version
              ON version.id = baseline.plan_version_id
            JOIN delivery_plans plan ON plan.id = version.plan_id
            WHERE baseline.id = fact_id;
        WHEN 'DELIVERY_SUBMISSION' THEN
            SELECT engagement_month_id INTO value
            FROM delivery_submissions WHERE id = fact_id;
        WHEN 'CERTIFICATION_ROUND' THEN
            SELECT engagement_month_id INTO value
            FROM certification_rounds WHERE id = fact_id;
        WHEN 'MONTHLY_CERTIFICATION_SUMMARY' THEN
            SELECT engagement_month_id INTO value
            FROM monthly_certification_summaries WHERE id = fact_id;
        WHEN 'BUSINESS_CONFIRMATION_REQUEST' THEN
            SELECT engagement_month_id INTO value
            FROM business_confirmation_requests WHERE id = fact_id;
        ELSE
            value := NULL;
    END CASE;
    RETURN value;
END;
$$;

CREATE OR REPLACE FUNCTION f04_fact_version(
    fact_type VARCHAR,
    fact_id UUID
)
RETURNS INTEGER
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
    value INTEGER;
BEGIN
    CASE fact_type
        WHEN 'ATTENDANCE_SNAPSHOT_VERSION' THEN
            SELECT version INTO value
            FROM attendance_snapshot_versions WHERE id = fact_id;
        WHEN 'DELIVERY_PLAN_VERSION' THEN
            SELECT version INTO value
            FROM delivery_plan_versions WHERE id = fact_id;
        WHEN 'DELIVERY_PLAN_BASELINE' THEN
            SELECT version.version INTO value
            FROM delivery_plan_baselines baseline
            JOIN delivery_plan_versions version
              ON version.id = baseline.plan_version_id
            WHERE baseline.id = fact_id;
        WHEN 'DELIVERY_SUBMISSION' THEN
            SELECT version INTO value
            FROM delivery_submissions WHERE id = fact_id;
        WHEN 'CERTIFICATION_ROUND' THEN
            SELECT round_number INTO value
            FROM certification_rounds WHERE id = fact_id;
        WHEN 'MONTHLY_CERTIFICATION_SUMMARY' THEN
            SELECT version INTO value
            FROM monthly_certification_summaries WHERE id = fact_id;
        WHEN 'BUSINESS_CONFIRMATION_REQUEST' THEN
            SELECT version INTO value
            FROM business_confirmation_requests WHERE id = fact_id;
        ELSE
            value := NULL;
    END CASE;
    RETURN value;
END;
$$;

CREATE OR REPLACE FUNCTION enforce_f04_invalidation_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF f04_fact_month(NEW.object_type, NEW.object_id)
           IS DISTINCT FROM NEW.engagement_month_id
       OR (
           NEW.reopen_request_id IS NOT NULL
           AND NOT EXISTS (
               SELECT 1
               FROM month_reopen_requests request
               WHERE request.id = NEW.reopen_request_id
                 AND request.engagement_month_id = NEW.engagement_month_id
           )
       ) THEN
        RAISE EXCEPTION 'Certification invalidation is outside typed month scope'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER f04_invalidation_scope_gate
BEFORE INSERT ON certification_invalidations
FOR EACH ROW EXECUTE FUNCTION enforce_f04_invalidation_scope();

-- A CLEARED resolution names the exact successor. SUPERSEDED remains available
-- for a rejected/withdrawn reopen or an explicit governance supersession.
ALTER TABLE certification_invalidation_resolutions
    ADD COLUMN corrected_object_type VARCHAR(64),
    ADD COLUMN corrected_object_id UUID,
    ADD COLUMN corrected_object_version INTEGER,
    ADD CONSTRAINT ck_f04_invalidation_corrected_fact CHECK (
        (
            resolution = 'CLEARED'
            AND corrected_object_type IS NOT NULL
            AND corrected_object_id IS NOT NULL
            AND corrected_object_version IS NOT NULL
            AND corrected_object_version > 0
        )
        OR (
            resolution = 'SUPERSEDED'
            AND corrected_object_type IS NULL
            AND corrected_object_id IS NULL
            AND corrected_object_version IS NULL
        )
    );

CREATE OR REPLACE FUNCTION f04_is_direct_successor(
    fact_type VARCHAR,
    invalidated_id UUID,
    corrected_id UUID
)
RETURNS BOOLEAN
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
    value BOOLEAN := FALSE;
BEGIN
    CASE fact_type
        WHEN 'ATTENDANCE_SNAPSHOT_VERSION' THEN
            SELECT EXISTS (
                SELECT 1 FROM attendance_snapshot_versions
                WHERE id = corrected_id AND supersedes_id = invalidated_id
            ) INTO value;
        WHEN 'DELIVERY_PLAN_VERSION' THEN
            SELECT EXISTS (
                SELECT 1 FROM delivery_plan_versions
                WHERE id = corrected_id AND prior_version_id = invalidated_id
            ) INTO value;
        WHEN 'DELIVERY_PLAN_BASELINE' THEN
            SELECT EXISTS (
                SELECT 1 FROM delivery_plan_baselines
                WHERE id = corrected_id AND original_baseline_id = invalidated_id
            ) INTO value;
        WHEN 'DELIVERY_SUBMISSION' THEN
            SELECT EXISTS (
                SELECT 1 FROM delivery_submissions
                WHERE id = corrected_id AND supersedes_id = invalidated_id
            ) INTO value;
        WHEN 'CERTIFICATION_ROUND' THEN
            SELECT EXISTS (
                SELECT 1 FROM certification_rounds
                WHERE id = corrected_id AND supersedes_id = invalidated_id
            ) INTO value;
        WHEN 'MONTHLY_CERTIFICATION_SUMMARY' THEN
            SELECT EXISTS (
                SELECT 1 FROM monthly_certification_summaries
                WHERE id = corrected_id AND supersedes_id = invalidated_id
            ) INTO value;
        WHEN 'BUSINESS_CONFIRMATION_REQUEST' THEN
            SELECT EXISTS (
                SELECT 1 FROM business_confirmation_requests
                WHERE id = corrected_id AND supersedes_id = invalidated_id
            ) INTO value;
        ELSE
            value := FALSE;
    END CASE;
    RETURN value;
END;
$$;

CREATE OR REPLACE FUNCTION f04_confirmed_request_contains_fact(
    fact_type VARCHAR,
    fact_id UUID,
    month_id UUID,
    invalidated_at TIMESTAMPTZ
)
RETURNS BOOLEAN
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
    value BOOLEAN := FALSE;
BEGIN
    CASE fact_type
        WHEN 'ATTENDANCE_SNAPSHOT_VERSION' THEN
            SELECT EXISTS (
                SELECT 1 FROM business_confirmation_requests request
                WHERE request.engagement_month_id = month_id
                  AND request.status = 'CONFIRMED'
                  AND request.completed_at > invalidated_at
                  AND request.attendance_snapshot_id = fact_id
            ) INTO value;
        WHEN 'DELIVERY_PLAN_VERSION' THEN
            SELECT EXISTS (
                SELECT 1 FROM business_confirmation_requests request
                WHERE request.engagement_month_id = month_id
                  AND request.status = 'CONFIRMED'
                  AND request.completed_at > invalidated_at
                  AND request.plan_version_id = fact_id
            ) INTO value;
        WHEN 'DELIVERY_PLAN_BASELINE' THEN
            SELECT EXISTS (
                SELECT 1 FROM business_confirmation_requests request
                WHERE request.engagement_month_id = month_id
                  AND request.status = 'CONFIRMED'
                  AND request.completed_at > invalidated_at
                  AND request.baseline_id = fact_id
            ) INTO value;
        WHEN 'DELIVERY_SUBMISSION' THEN
            SELECT EXISTS (
                SELECT 1
                FROM business_confirmation_requests request
                JOIN monthly_certification_summaries summary
                  ON summary.id = request.certification_summary_id
                WHERE request.engagement_month_id = month_id
                  AND request.status = 'CONFIRMED'
                  AND request.completed_at > invalidated_at
                  AND summary.submission_id = fact_id
            ) INTO value;
        WHEN 'CERTIFICATION_ROUND' THEN
            SELECT EXISTS (
                SELECT 1
                FROM business_confirmation_requests request
                JOIN monthly_certification_summaries summary
                  ON summary.id = request.certification_summary_id
                WHERE request.engagement_month_id = month_id
                  AND request.status = 'CONFIRMED'
                  AND request.completed_at > invalidated_at
                  AND summary.round_id = fact_id
            ) INTO value;
        WHEN 'MONTHLY_CERTIFICATION_SUMMARY' THEN
            SELECT EXISTS (
                SELECT 1 FROM business_confirmation_requests request
                WHERE request.engagement_month_id = month_id
                  AND request.status = 'CONFIRMED'
                  AND request.completed_at > invalidated_at
                  AND request.certification_summary_id = fact_id
            ) INTO value;
        WHEN 'BUSINESS_CONFIRMATION_REQUEST' THEN
            SELECT EXISTS (
                SELECT 1 FROM business_confirmation_requests request
                WHERE request.id = fact_id
                  AND request.engagement_month_id = month_id
                  AND request.status = 'CONFIRMED'
                  AND request.completed_at > invalidated_at
            ) INTO value;
        ELSE
            value := FALSE;
    END CASE;
    RETURN value;
END;
$$;

CREATE OR REPLACE FUNCTION enforce_f04_invalidation_resolution_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    source certification_invalidations%ROWTYPE;
BEGIN
    SELECT * INTO source
    FROM certification_invalidations
    WHERE id = NEW.invalidation_id;

    IF source.id IS NULL THEN
        RAISE EXCEPTION 'Invalidation resolution source is missing'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.resolution = 'CLEARED'
       AND (
           NEW.corrected_object_type <> source.object_type
           OR f04_fact_month(
               NEW.corrected_object_type, NEW.corrected_object_id
           ) IS DISTINCT FROM source.engagement_month_id
           OR f04_fact_version(
               NEW.corrected_object_type, NEW.corrected_object_id
           ) IS DISTINCT FROM NEW.corrected_object_version
           OR NOT f04_is_direct_successor(
               source.object_type, source.object_id,
               NEW.corrected_object_id
           )
           OR NOT f04_confirmed_request_contains_fact(
               NEW.corrected_object_type, NEW.corrected_object_id,
               source.engagement_month_id, source.created_at
           )
       ) THEN
        RAISE EXCEPTION 'Cleared invalidation requires its exact confirmed successor'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.resolution = 'SUPERSEDED'
       AND NOT EXISTS (
           SELECT 1
           FROM month_reopen_decisions decision
           WHERE decision.reopen_request_id = source.reopen_request_id
             AND decision.decision = 'REJECT'
       ) THEN
        RAISE EXCEPTION 'Superseded invalidation requires a rejected reopen'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER f04_invalidation_resolution_scope_gate
BEFORE INSERT ON certification_invalidation_resolutions
FOR EACH ROW EXECUTE FUNCTION enforce_f04_invalidation_resolution_scope();

-- A reviewed inbound/manual fact is promoted exactly once and remains visibly
-- bound to the review, represented timestamp, evidence hash, request version,
-- actor and business action that contributed to quorum.
CREATE TABLE reviewed_confirmation_action_promotions (
    id UUID PRIMARY KEY,
    action_id UUID NOT NULL UNIQUE REFERENCES business_confirmation_actions(id),
    request_id UUID NOT NULL REFERENCES business_confirmation_requests(id),
    request_version INTEGER NOT NULL CHECK (request_version > 0),
    source_type VARCHAR(24) NOT NULL CHECK (
        source_type IN ('INBOUND_MESSAGE', 'MANUAL_EVIDENCE')
    ),
    source_id UUID NOT NULL,
    review_id UUID NOT NULL,
    represented_actor_subject VARCHAR(255) NOT NULL,
    represented_at TIMESTAMPTZ NOT NULL,
    evidence_hash VARCHAR(64) NOT NULL CHECK (
        evidence_hash ~ '^[0-9a-f]{64}$'
    ),
    promoted_by_subject VARCHAR(255) NOT NULL,
    correlation_id UUID NOT NULL,
    promoted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (source_type, source_id)
);

CREATE OR REPLACE FUNCTION enforce_f04_reviewed_action_promotion_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    promoted_action business_confirmation_actions%ROWTYPE;
BEGIN
    SELECT * INTO promoted_action
    FROM business_confirmation_actions
    WHERE id = NEW.action_id;

    IF promoted_action.id IS NULL
       OR promoted_action.request_id <> NEW.request_id
       OR promoted_action.request_version <> NEW.request_version
       OR promoted_action.actor_subject <> NEW.represented_actor_subject
       OR promoted_action.represented_at IS DISTINCT FROM NEW.represented_at
       OR promoted_action.session_evidence_hash IS DISTINCT FROM NEW.evidence_hash
       OR NOT EXISTS (
           SELECT 1
           FROM confirmation_request_eligibility eligibility
           WHERE eligibility.request_id = NEW.request_id
             AND eligibility.eligible_confirmer_subject =
                 NEW.represented_actor_subject
             AND eligibility.project_id IS NOT DISTINCT FROM
                 promoted_action.project_id
       ) THEN
        RAISE EXCEPTION 'Reviewed confirmation action is outside request scope'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.source_type = 'INBOUND_MESSAGE' AND (
        promoted_action.source <> 'VERIFIED_EMAIL_REPLY'
        OR promoted_action.verification_status <> 'VERIFIED'
        OR NOT EXISTS (
            SELECT 1
            FROM inbound_confirmation_messages message
            JOIN inbound_confirmation_reviews review
              ON review.inbound_message_id = message.id
             AND review.id = NEW.review_id
             AND review.decision = 'ACCEPT_INTERPRETATION'
             AND review.reviewer_subject = NEW.promoted_by_subject
            JOIN confirmation_request_eligibility eligibility
              ON eligibility.request_id = message.request_id
             AND eligibility.eligible_confirmer_subject =
                 NEW.represented_actor_subject
            JOIN confirmation_eligibility_snapshots snapshot
              ON snapshot.id = eligibility.eligibility_id
             AND encode(
                 sha256(convert_to(lower(trim(snapshot.verified_email)), 'UTF8')),
                 'hex'
             ) = message.sender_address_hash
            WHERE message.id = NEW.source_id
              AND message.request_id = NEW.request_id
              AND message.provider_received_at = NEW.represented_at
              AND message.authentication_evidence @> '{"verified":true}'::jsonb
              AND (
                  (message.classified_intent = 'EXPLICIT_CONFIRM'
                      AND promoted_action.action = 'CONFIRM')
                  OR (message.classified_intent = 'EXPLICIT_CORRECTION'
                      AND promoted_action.action = 'REQUEST_CORRECTION')
                  OR (message.classified_intent = 'EXPLICIT_REJECT'
                      AND promoted_action.action = 'REJECT')
              )
        )
    ) THEN
        RAISE EXCEPTION 'Inbound promotion lacks an accepted verified reply'
            USING ERRCODE = '23514';
    ELSIF NEW.source_type = 'MANUAL_EVIDENCE' AND (
        promoted_action.source <> 'MANUAL_EVIDENCE'
        OR promoted_action.verification_status <> 'MANUAL_REVIEWED'
        OR NOT EXISTS (
            SELECT 1
            FROM manual_confirmation_evidence evidence
            JOIN manual_confirmation_evidence_reviews review
              ON review.manual_evidence_id = evidence.id
             AND review.id = NEW.review_id
             AND review.decision = 'APPROVE'
             AND review.reviewer_subject = NEW.promoted_by_subject
            JOIN confirmation_request_eligibility eligibility
              ON eligibility.request_id = evidence.request_id
             AND eligibility.eligible_confirmer_subject =
                 NEW.represented_actor_subject
            JOIN confirmation_eligibility_snapshots snapshot
              ON snapshot.id = eligibility.eligibility_id
             AND encode(
                 sha256(convert_to(lower(trim(snapshot.verified_email)), 'UTF8')),
                 'hex'
             ) = encode(
                 sha256(convert_to(lower(trim(evidence.sender_address)), 'UTF8')),
                 'hex'
             )
            WHERE evidence.id = NEW.source_id
              AND evidence.request_id = NEW.request_id
              AND evidence.sent_or_received_at = NEW.represented_at
              AND evidence.file_hash = NEW.evidence_hash
              AND evidence.represented_decision = promoted_action.action
              AND evidence.recorded_by_subject <> review.reviewer_subject
        )
    ) THEN
        RAISE EXCEPTION 'Manual promotion lacks an approved exact evidence fact'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER f04_reviewed_action_promotion_scope_gate
BEFORE INSERT ON reviewed_confirmation_action_promotions
FOR EACH ROW EXECUTE FUNCTION enforce_f04_reviewed_action_promotion_scope();
CREATE TRIGGER f04_reviewed_action_promotions_immutable
BEFORE UPDATE OR DELETE ON reviewed_confirmation_action_promotions
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

-- Approved reopen is a durable cancellation fence.  A claimed job is
-- tombstoned as well, so an expired lease cannot resurrect invalidated work.
ALTER TABLE f05_handoff_publish_jobs
    DROP CONSTRAINT f05_handoff_publish_jobs_status_check,
    ADD COLUMN cancelled_at TIMESTAMPTZ,
    ADD COLUMN cancellation_invalidation_id UUID
        REFERENCES f05_handoff_invalidations(id),
    ADD CONSTRAINT ck_f05_handoff_publish_job_status_v13 CHECK (
        status IN (
            'PENDING', 'CLAIMED', 'COMPLETED', 'DEAD_LETTER', 'CANCELLED'
        )
    ),
    ADD CONSTRAINT ck_f05_handoff_publish_job_cancelled CHECK (
        (
            status = 'CANCELLED'
            AND cancelled_at IS NOT NULL
            AND cancellation_invalidation_id IS NOT NULL
            AND lease_owner IS NULL
            AND lease_expires_at IS NULL
        )
        OR (
            status <> 'CANCELLED'
            AND cancelled_at IS NULL
            AND cancellation_invalidation_id IS NULL
        )
    );

CREATE OR REPLACE FUNCTION cancel_f04_invalidated_handoff_job()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    UPDATE f05_handoff_publish_jobs
    SET status = 'CANCELLED',
        next_attempt_at = NULL,
        lease_owner = NULL,
        lease_expires_at = NULL,
        last_error_code = 'HANDOFF_INVALIDATED',
        cancelled_at = NEW.invalidated_at,
        cancellation_invalidation_id = NEW.id
    WHERE handoff_id = NEW.handoff_id
      AND status IN ('PENDING', 'CLAIMED', 'COMPLETED', 'DEAD_LETTER');
    RETURN NEW;
END;
$$;

CREATE TRIGGER f04_handoff_job_cancellation_gate
AFTER INSERT ON f05_handoff_invalidations
FOR EACH ROW EXECUTE FUNCTION cancel_f04_invalidated_handoff_job();
