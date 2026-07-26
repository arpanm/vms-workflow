-- F03 release hardening. V7 remains an append-only historical migration.

ALTER TABLE linear_connections
    ADD COLUMN provider_team_id VARCHAR(128);

CREATE TABLE linear_recorded_issue_metadata (
    connection_id UUID NOT NULL REFERENCES linear_connections(id),
    linear_issue_uuid UUID NOT NULL,
    provider_organization_id VARCHAR(128) NOT NULL,
    provider_team_id VARCHAR(128) NOT NULL,
    identifier VARCHAR(64) NOT NULL,
    issue_url VARCHAR(512) NOT NULL,
    title VARCHAR(512) NOT NULL,
    provider_state_id VARCHAR(128),
    provider_state_name VARCHAR(128),
    provider_state_type VARCHAR(64) NOT NULL,
    provider_state_category VARCHAR(64),
    provider_updated_at TIMESTAMPTZ NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (connection_id, linear_issue_uuid)
);

CREATE TRIGGER linear_recorded_issue_metadata_immutable
BEFORE UPDATE OR DELETE ON linear_recorded_issue_metadata
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

CREATE TABLE delivery_execution_projections (
    deliverable_version_id UUID PRIMARY KEY
        REFERENCES delivery_deliverable_versions(id),
    execution_projection VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN' CHECK (
        execution_projection IN (
            'BACKLOG', 'UNSTARTED', 'STARTED', 'COMPLETED',
            'CANCELED', 'UNKNOWN'
        )
    ),
    source_event_id UUID REFERENCES linear_issue_events(id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO delivery_execution_projections
    (deliverable_version_id, execution_projection)
SELECT id, execution_projection
FROM delivery_deliverable_versions;

CREATE TABLE delivery_execution_projection_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    deliverable_version_id UUID NOT NULL
        REFERENCES delivery_deliverable_versions(id),
    previous_projection VARCHAR(24),
    execution_projection VARCHAR(24) NOT NULL,
    source_event_id UUID REFERENCES linear_issue_events(id),
    changed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE OR REPLACE FUNCTION audit_delivery_execution_projection()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT'
       OR NEW.execution_projection IS DISTINCT FROM OLD.execution_projection
       OR NEW.source_event_id IS DISTINCT FROM OLD.source_event_id THEN
        INSERT INTO delivery_execution_projection_events
            (deliverable_version_id, previous_projection, execution_projection,
             source_event_id)
        VALUES (
            NEW.deliverable_version_id,
            CASE WHEN TG_OP = 'INSERT' THEN NULL ELSE OLD.execution_projection END,
            NEW.execution_projection,
            NEW.source_event_id
        );
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER delivery_execution_projection_audit
AFTER INSERT OR UPDATE ON delivery_execution_projections
FOR EACH ROW EXECUTE FUNCTION audit_delivery_execution_projection();

CREATE TRIGGER delivery_execution_projection_events_immutable
BEFORE UPDATE OR DELETE ON delivery_execution_projection_events
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

ALTER TABLE linear_webhook_deliveries
    ADD COLUMN raw_body BYTEA;

UPDATE linear_webhook_deliveries
SET raw_body = convert_to(raw_payload::text, 'UTF8')
WHERE raw_body IS NULL;

ALTER TABLE linear_webhook_deliveries
    ALTER COLUMN raw_body SET NOT NULL;

ALTER TABLE linear_issue_events
    ADD COLUMN processing_disposition VARCHAR(24) NOT NULL DEFAULT 'APPLIED'
        CHECK (processing_disposition IN ('APPLIED', 'STALE_IGNORED', 'DUPLICATE'));

CREATE TABLE linear_webhook_audit_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    delivery_id UUID REFERENCES linear_webhook_deliveries(delivery_id),
    connection_id UUID NOT NULL REFERENCES linear_connections(id),
    event_type VARCHAR(64) NOT NULL,
    facts JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TRIGGER linear_webhook_audit_events_immutable
BEFORE UPDATE OR DELETE ON linear_webhook_audit_events
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

ALTER TABLE linear_webhook_queue
    ADD COLUMN claimed_by VARCHAR(128),
    ADD COLUMN claimed_at TIMESTAMPTZ,
    ADD COLUMN last_attempt_at TIMESTAMPTZ;

CREATE INDEX idx_linear_webhook_queue_claim
    ON linear_webhook_queue (status, available_at, created_at)
    WHERE status = 'QUEUED';

CREATE OR REPLACE FUNCTION delivery_version_content_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    approver_count INTEGER;
    eligible_count INTEGER;
BEGIN
    IF NEW.state = 'PENDING_APPROVAL'
       AND OLD.state IS DISTINCT FROM 'PENDING_APPROVAL' THEN
        SELECT COUNT(*),
               COUNT(*) FILTER (
                   WHERE authority_snapshot @> '{"eligible":true}'::jsonb)
        INTO approver_count, eligible_count
        FROM delivery_plan_approvers
        WHERE plan_version_id = NEW.id;
        IF approver_count = 0
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
            RAISE EXCEPTION 'Submission lacks valid scoped authority or separation of duties'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    IF OLD.state IN ('PENDING_APPROVAL', 'FROZEN', 'SUPERSEDED') THEN
        IF OLD.state = 'PENDING_APPROVAL'
           AND NEW.state IN ('FROZEN', 'REJECTED')
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
           AND NEW.checksum IS NOT DISTINCT FROM OLD.checksum
           AND NEW.submitted_at IS NOT DISTINCT FROM OLD.submitted_at
           AND NEW.created_by_subject IS NOT DISTINCT FROM OLD.created_by_subject
           AND NEW.created_at IS NOT DISTINCT FROM OLD.created_at THEN
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'Protected delivery plan version content is immutable'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER delivery_plan_versions_content_immutable
BEFORE UPDATE ON delivery_plan_versions
FOR EACH ROW EXECUTE FUNCTION delivery_version_content_guard();

CREATE OR REPLACE FUNCTION protected_delivery_version_state(p_version_id UUID)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
AS $$
    SELECT EXISTS (
        SELECT 1 FROM delivery_plan_versions
        WHERE id = p_version_id
          AND state IN ('PENDING_APPROVAL', 'FROZEN', 'SUPERSEDED')
    );
$$;

CREATE OR REPLACE FUNCTION delivery_owned_content_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    version_id UUID;
    old_version_id UUID;
BEGIN
    IF TG_TABLE_NAME IN ('delivery_plan_approvers', 'delivery_recipient_snapshots') THEN
        version_id := CASE WHEN TG_OP = 'DELETE' THEN OLD.plan_version_id
                           ELSE NEW.plan_version_id END;
        old_version_id := CASE WHEN TG_OP = 'INSERT' THEN NULL
                               ELSE OLD.plan_version_id END;
    ELSIF TG_TABLE_NAME = 'delivery_deliverable_versions' THEN
        version_id := CASE WHEN TG_OP = 'DELETE' THEN OLD.plan_version_id
                           ELSE NEW.plan_version_id END;
        old_version_id := CASE WHEN TG_OP = 'INSERT' THEN NULL
                               ELSE OLD.plan_version_id END;
    ELSIF TG_TABLE_NAME IN (
        'delivery_acceptance_criteria', 'delivery_dependencies',
        'delivery_employee_assignments', 'linear_issue_links'
    ) THEN
        SELECT plan_version_id INTO version_id
        FROM delivery_deliverable_versions
        WHERE id = CASE WHEN TG_OP = 'DELETE' THEN OLD.deliverable_version_id
                        ELSE NEW.deliverable_version_id END;
        IF TG_OP <> 'INSERT' THEN
            SELECT plan_version_id INTO old_version_id
            FROM delivery_deliverable_versions
            WHERE id = OLD.deliverable_version_id;
        END IF;
    ELSE
        RAISE EXCEPTION 'Unsupported protected content table %', TG_TABLE_NAME;
    END IF;

    IF protected_delivery_version_state(version_id)
       OR (old_version_id IS NOT NULL
           AND protected_delivery_version_state(old_version_id)) THEN
        RAISE EXCEPTION 'Protected delivery version-owned content is immutable'
            USING ERRCODE = '55000';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$;

CREATE TRIGGER delivery_plan_approvers_content_immutable
BEFORE INSERT OR UPDATE OR DELETE ON delivery_plan_approvers
FOR EACH ROW EXECUTE FUNCTION delivery_owned_content_guard();
CREATE TRIGGER delivery_recipient_snapshots_content_immutable
BEFORE INSERT OR UPDATE OR DELETE ON delivery_recipient_snapshots
FOR EACH ROW EXECUTE FUNCTION delivery_owned_content_guard();
CREATE TRIGGER delivery_deliverable_versions_content_immutable
BEFORE INSERT OR UPDATE OR DELETE ON delivery_deliverable_versions
FOR EACH ROW EXECUTE FUNCTION delivery_owned_content_guard();
CREATE TRIGGER delivery_acceptance_criteria_content_immutable
BEFORE INSERT OR UPDATE OR DELETE ON delivery_acceptance_criteria
FOR EACH ROW EXECUTE FUNCTION delivery_owned_content_guard();
CREATE TRIGGER delivery_dependencies_content_immutable
BEFORE INSERT OR UPDATE OR DELETE ON delivery_dependencies
FOR EACH ROW EXECUTE FUNCTION delivery_owned_content_guard();
CREATE TRIGGER delivery_employee_assignments_content_immutable
BEFORE INSERT OR UPDATE OR DELETE ON delivery_employee_assignments
FOR EACH ROW EXECUTE FUNCTION delivery_owned_content_guard();
CREATE TRIGGER linear_issue_links_content_immutable
BEFORE INSERT OR UPDATE OR DELETE ON linear_issue_links
FOR EACH ROW EXECUTE FUNCTION delivery_owned_content_guard();

CREATE OR REPLACE FUNCTION enforce_approval_snapshot_and_sod()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM delivery_plan_approvers approver
        WHERE approver.plan_version_id = NEW.plan_version_id
          AND approver.approver_subject = NEW.approver_subject
          AND approver.authority_snapshot @> '{"eligible":true}'::jsonb
    ) THEN
        RAISE EXCEPTION 'Approval lacks an eligible authority snapshot'
            USING ERRCODE = '23514';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM delivery_plan_versions version
        WHERE version.id = NEW.plan_version_id
          AND (
              NEW.approver_subject = version.created_by_subject
              OR NEW.approver_subject = version.coordinator_subject
              OR EXISTS (
                  SELECT 1 FROM delivery_deliverable_versions deliverable
                  WHERE deliverable.plan_version_id = version.id
                    AND NEW.approver_subject IN (
                        deliverable.product_owner_subject,
                        deliverable.vendor_owner_subject
                    )
              )
          )
    ) THEN
        RAISE EXCEPTION 'Approval violates separation of duties'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER delivery_approval_authority_gate
BEFORE INSERT ON delivery_plan_approvals
FOR EACH ROW EXECUTE FUNCTION enforce_approval_snapshot_and_sod();

ALTER TABLE delivery_plan_versions
    ADD CONSTRAINT ck_delivery_quorum_shape CHECK (
        (quorum_mode = 'ANY_ONE' AND quorum_required = 1)
        OR quorum_mode IN ('ALL', 'N_OF_M')
    ) NOT VALID;

ALTER TABLE delivery_plan_versions
    VALIDATE CONSTRAINT ck_delivery_quorum_shape;
