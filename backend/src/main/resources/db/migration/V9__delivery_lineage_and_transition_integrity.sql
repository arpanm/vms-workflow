-- Second-review integrity hardening. V7 and V8 remain unchanged history.

CREATE OR REPLACE FUNCTION delivery_stable_lineage_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM delivery_deliverable_versions version
        WHERE version.deliverable_id = OLD.id
    ) THEN
        RAISE EXCEPTION 'Stable deliverable identity and plan lineage are immutable'
            USING ERRCODE = '55000';
    END IF;
    IF TG_OP = 'UPDATE'
       AND (
           NEW.id IS DISTINCT FROM OLD.id
           OR NEW.plan_id IS DISTINCT FROM OLD.plan_id
           OR NEW.deliverable_code IS DISTINCT FROM OLD.deliverable_code
           OR NEW.created_at IS DISTINCT FROM OLD.created_at
       ) THEN
        RAISE EXCEPTION 'Stable deliverable identity and plan lineage are immutable'
            USING ERRCODE = '55000';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$;

CREATE TRIGGER delivery_deliverables_lineage_immutable
BEFORE UPDATE OR DELETE ON delivery_deliverables
FOR EACH ROW EXECUTE FUNCTION delivery_stable_lineage_guard();

CREATE OR REPLACE FUNCTION delivery_version_content_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    approver_count INTEGER;
    eligible_count INTEGER;
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
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'Unsupported delivery plan version state transition'
        USING ERRCODE = '55000';
END;
$$;
