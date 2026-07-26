-- Close the remaining terminal-state evidence and lineage gaps without
-- rewriting the historical V7-V9 migrations.

CREATE OR REPLACE FUNCTION protected_delivery_version_state(p_version_id UUID)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM delivery_plan_versions
        WHERE id = p_version_id
          AND state IN (
              'PENDING_APPROVAL', 'FROZEN', 'SUPERSEDED', 'REJECTED'
          )
    );
$$;

CREATE OR REPLACE FUNCTION delivery_version_content_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    approver_count INTEGER;
    eligible_count INTEGER;
    required_approval_count INTEGER;
    matching_approval_count INTEGER;
    identity_and_content_unchanged BOOLEAN;
BEGIN
    identity_and_content_unchanged :=
        NEW.id IS NOT DISTINCT FROM OLD.id
        AND NEW.plan_id IS NOT DISTINCT FROM OLD.plan_id
        AND NEW.version IS NOT DISTINCT FROM OLD.version
        AND NEW.title IS NOT DISTINCT FROM OLD.title
        AND NEW.summary IS NOT DISTINCT FROM OLD.summary
        AND NEW.business_outcomes IS NOT DISTINCT FROM OLD.business_outcomes
        AND NEW.coordinator_subject IS NOT DISTINCT FROM OLD.coordinator_subject
        AND NEW.baseline_type IS NOT DISTINCT FROM OLD.baseline_type
        AND NEW.quorum_mode IS NOT DISTINCT FROM OLD.quorum_mode
        AND NEW.quorum_required IS NOT DISTINCT FROM OLD.quorum_required
        AND NEW.prior_version_id IS NOT DISTINCT FROM OLD.prior_version_id
        AND NEW.revision_reason IS NOT DISTINCT FROM OLD.revision_reason
        AND NEW.revision_impact IS NOT DISTINCT FROM OLD.revision_impact
        AND NEW.created_at IS NOT DISTINCT FROM OLD.created_at
        AND NEW.created_by_subject IS NOT DISTINCT FROM OLD.created_by_subject;

    IF OLD.state = 'DRAFT' AND NEW.state = 'DRAFT' THEN
        IF NEW.id IS DISTINCT FROM OLD.id
           OR NEW.plan_id IS DISTINCT FROM OLD.plan_id
           OR NEW.version IS DISTINCT FROM OLD.version
           OR NEW.checksum IS DISTINCT FROM OLD.checksum
           OR NEW.optimistic_version IS DISTINCT FROM OLD.optimistic_version
           OR NEW.submitted_at IS DISTINCT FROM OLD.submitted_at
           OR NEW.frozen_at IS DISTINCT FROM OLD.frozen_at
           OR NEW.created_at IS DISTINCT FROM OLD.created_at
           OR NEW.created_by_subject IS DISTINCT FROM OLD.created_by_subject THEN
            RAISE EXCEPTION 'Draft identity and lifecycle fields are immutable'
                USING ERRCODE = '55000';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.state = 'DRAFT' AND NEW.state = 'PENDING_APPROVAL' THEN
        SELECT COUNT(*),
               COUNT(*) FILTER (
                   WHERE authority_snapshot @> '{"eligible":true}'::jsonb)
        INTO approver_count, eligible_count
        FROM delivery_plan_approvers
        WHERE plan_version_id = NEW.id;

        IF NOT identity_and_content_unchanged
           OR NEW.checksum IS NULL
           OR NEW.checksum !~ '^[0-9a-f]{64}$'
           OR NEW.optimistic_version <> OLD.optimistic_version + 1
           OR NEW.submitted_at IS NULL
           OR NEW.frozen_at IS DISTINCT FROM OLD.frozen_at
           OR approver_count = 0
           OR eligible_count <> approver_count
           OR (NEW.quorum_mode = 'ANY_ONE' AND NEW.quorum_required <> 1)
           OR (NEW.quorum_mode = 'ALL'
               AND NEW.quorum_required <> approver_count)
           OR (NEW.quorum_mode = 'N_OF_M'
               AND NEW.quorum_required > approver_count)
           OR EXISTS (
               SELECT 1
               FROM delivery_plan_approvers approver
               WHERE approver.plan_version_id = NEW.id
                 AND (
                     approver.approver_subject = NEW.created_by_subject
                     OR approver.approver_subject = NEW.coordinator_subject
                     OR EXISTS (
                         SELECT 1
                         FROM delivery_deliverable_versions deliverable
                         WHERE deliverable.plan_version_id = NEW.id
                           AND approver.approver_subject IN (
                               deliverable.product_owner_subject,
                               deliverable.vendor_owner_subject
                           )
                     )
                 )
           ) THEN
            RAISE EXCEPTION 'Invalid draft-to-pending transition or authority snapshot'
                USING ERRCODE = '55000';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.state = 'PENDING_APPROVAL'
       AND NEW.state IN ('FROZEN', 'REJECTED') THEN
        IF NOT identity_and_content_unchanged
           OR NEW.checksum IS DISTINCT FROM OLD.checksum
           OR NEW.submitted_at IS DISTINCT FROM OLD.submitted_at
           OR NEW.optimistic_version <> OLD.optimistic_version + 1
           OR (
               NEW.state = 'FROZEN'
               AND (
                   NEW.frozen_at IS NULL
                   OR OLD.frozen_at IS NOT NULL
                   OR NEW.frozen_at < NEW.submitted_at
               )
           )
           OR (
               NEW.state = 'REJECTED'
               AND NEW.frozen_at IS DISTINCT FROM OLD.frozen_at
           ) THEN
            RAISE EXCEPTION 'Invalid pending terminal transition or immutable-column change'
                USING ERRCODE = '55000';
        END IF;

        IF NEW.state = 'REJECTED' THEN
            IF NOT EXISTS (
                SELECT 1
                FROM delivery_plan_approvals approval
                WHERE approval.plan_version_id = NEW.id
                  AND approval.decision = 'REJECT'
                  AND approval.signed_checksum = NEW.checksum
            ) THEN
                RAISE EXCEPTION 'Rejection requires an attributable signed reject vote'
                    USING ERRCODE = '55000';
            END IF;
            RETURN NEW;
        END IF;

        SELECT COUNT(*)
        INTO approver_count
        FROM delivery_plan_approvers
        WHERE plan_version_id = NEW.id
          AND authority_snapshot @> '{"eligible":true}'::jsonb;

        required_approval_count := CASE NEW.quorum_mode
            WHEN 'ANY_ONE' THEN 1
            WHEN 'ALL' THEN approver_count
            WHEN 'N_OF_M' THEN NEW.quorum_required
            ELSE NULL
        END;

        SELECT COUNT(*)
        INTO matching_approval_count
        FROM delivery_plan_approvals approval
        JOIN delivery_plan_approvers approver
          ON approver.plan_version_id = approval.plan_version_id
         AND approver.approver_subject = approval.approver_subject
         AND approver.authority_snapshot @> '{"eligible":true}'::jsonb
        WHERE approval.plan_version_id = NEW.id
          AND approval.decision = 'APPROVE'
          AND approval.signed_checksum = NEW.checksum;

        IF required_approval_count IS NULL
           OR required_approval_count <= 0
           OR matching_approval_count < required_approval_count
           OR EXISTS (
               SELECT 1
               FROM delivery_plan_approvals approval
               WHERE approval.plan_version_id = NEW.id
                 AND approval.decision = 'REJECT'
           )
           OR NOT EXISTS (
               SELECT 1
               FROM delivery_plan_baselines baseline
               WHERE baseline.plan_version_id = NEW.id
                 AND baseline.checksum = NEW.checksum
                 AND baseline.deliverable_count = (
                     SELECT COUNT(*)
                     FROM delivery_deliverable_versions deliverable
                     WHERE deliverable.plan_version_id = NEW.id
                 )
           )
           OR NOT EXISTS (
               SELECT 1
               FROM commitment_outbox outbox
               JOIN delivery_plan_baselines baseline
                 ON baseline.id = outbox.baseline_id
               WHERE outbox.plan_version_id = NEW.id
                 AND baseline.plan_version_id = NEW.id
                 AND baseline.checksum = NEW.checksum
                 AND outbox.idempotency_key = 'commitment:' || NEW.id::text
           )
           OR NOT EXISTS (
               SELECT 1
               FROM delivery_audit_events audit
               JOIN delivery_plan_approvals approval
                 ON approval.plan_version_id = audit.plan_version_id
                AND approval.approver_subject = audit.actor_subject
                AND approval.decision = 'APPROVE'
                AND approval.signed_checksum = NEW.checksum
               WHERE audit.plan_id = NEW.plan_id
                 AND audit.plan_version_id = NEW.id
                 AND audit.event_type = 'PLAN_FROZEN'
           ) THEN
            RAISE EXCEPTION 'Freeze requires signed quorum and atomic commitment evidence'
                USING ERRCODE = '55000';
        END IF;
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'Unsupported delivery plan version state transition'
        USING ERRCODE = '55000';
END;
$$;

CREATE OR REPLACE FUNCTION enforce_delivery_deliverable_plan_lineage()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM delivery_deliverables deliverable
        JOIN delivery_plan_versions version
          ON version.id = NEW.plan_version_id
        WHERE deliverable.id = NEW.deliverable_id
          AND deliverable.plan_id = version.plan_id
    ) THEN
        RAISE EXCEPTION 'Deliverable identity and version belong to different plans'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM delivery_deliverable_versions deliverable_version
        JOIN delivery_deliverables deliverable
          ON deliverable.id = deliverable_version.deliverable_id
        JOIN delivery_plan_versions version
          ON version.id = deliverable_version.plan_version_id
        WHERE deliverable.plan_id <> version.plan_id
    ) THEN
        RAISE EXCEPTION
            'Existing deliverable-version lineage crosses plan ownership'
            USING ERRCODE = '23514';
    END IF;
END;
$$;

CREATE TRIGGER delivery_deliverable_version_plan_lineage_gate
BEFORE INSERT OR UPDATE OF deliverable_id, plan_version_id
ON delivery_deliverable_versions
FOR EACH ROW EXECUTE FUNCTION enforce_delivery_deliverable_plan_lineage();
