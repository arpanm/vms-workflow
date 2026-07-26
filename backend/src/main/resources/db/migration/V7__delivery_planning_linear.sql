CREATE TABLE delivery_plans (
    id UUID PRIMARY KEY,
    engagement_month_id UUID NOT NULL UNIQUE REFERENCES engagement_months(id),
    current_version_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_subject VARCHAR(255) NOT NULL
);

CREATE TABLE delivery_plan_versions (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL REFERENCES delivery_plans(id),
    version INTEGER NOT NULL CHECK (version > 0),
    state VARCHAR(32) NOT NULL CHECK (state IN (
        'DRAFT', 'READY_FOR_REVIEW', 'PENDING_APPROVAL', 'APPROVED',
        'FROZEN', 'SUPERSEDED', 'CHANGES_REQUESTED', 'REJECTED', 'CANCELLED'
    )),
    title VARCHAR(256) NOT NULL,
    summary TEXT NOT NULL,
    business_outcomes TEXT NOT NULL,
    coordinator_subject VARCHAR(255) NOT NULL,
    baseline_type VARCHAR(32) NOT NULL CHECK (baseline_type IN (
        'ON_TIME', 'LATE_APPROVED', 'HISTORICAL_RECONSTRUCTED'
    )),
    quorum_mode VARCHAR(16) NOT NULL CHECK (quorum_mode IN ('ANY_ONE', 'ALL', 'N_OF_M')),
    quorum_required INTEGER NOT NULL CHECK (quorum_required > 0),
    prior_version_id UUID REFERENCES delivery_plan_versions(id),
    revision_reason TEXT,
    revision_impact TEXT,
    checksum VARCHAR(64),
    optimistic_version INTEGER NOT NULL DEFAULT 0,
    submitted_at TIMESTAMPTZ,
    frozen_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_subject VARCHAR(255) NOT NULL,
    UNIQUE (plan_id, version),
    CONSTRAINT ck_delivery_revision_reason CHECK (
        prior_version_id IS NULL
        OR (revision_reason IS NOT NULL AND revision_impact IS NOT NULL)
    )
);

ALTER TABLE delivery_plans
    ADD CONSTRAINT fk_delivery_current_version
        FOREIGN KEY (current_version_id) REFERENCES delivery_plan_versions(id);

CREATE TABLE delivery_plan_approvers (
    plan_version_id UUID NOT NULL REFERENCES delivery_plan_versions(id),
    approver_subject VARCHAR(255) NOT NULL,
    authority_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (plan_version_id, approver_subject)
);

CREATE TABLE delivery_recipient_snapshots (
    plan_version_id UUID PRIMARY KEY REFERENCES delivery_plan_versions(id),
    arrow_foundry JSONB NOT NULL,
    reliance_stakeholders JSONB NOT NULL,
    procurement_cc JSONB NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE delivery_deliverables (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL REFERENCES delivery_plans(id),
    deliverable_code VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (plan_id, deliverable_code)
);

CREATE TABLE delivery_deliverable_versions (
    id UUID PRIMARY KEY,
    deliverable_id UUID NOT NULL REFERENCES delivery_deliverables(id),
    plan_version_id UUID NOT NULL REFERENCES delivery_plan_versions(id),
    project_id UUID NOT NULL REFERENCES projects(id),
    title VARCHAR(256) NOT NULL,
    description TEXT NOT NULL,
    business_objective TEXT NOT NULL,
    product_owner_subject VARCHAR(255) NOT NULL,
    vendor_owner_subject VARCHAR(255) NOT NULL,
    priority VARCHAR(32) NOT NULL,
    target_completion_date DATE NOT NULL,
    evidence_expectations TEXT NOT NULL,
    dependency_none_declared BOOLEAN NOT NULL,
    risk_and_assumptions TEXT NOT NULL,
    delivery_category VARCHAR(32) NOT NULL CHECK (delivery_category IN (
        'FEATURE', 'PLATFORM', 'INTEGRATION', 'QUALITY', 'OPERATIONS',
        'RESEARCH_POC', 'SUPPORT', 'OTHER'
    )),
    link_exception_reason TEXT,
    execution_projection VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN' CHECK (
        execution_projection IN (
            'BACKLOG', 'UNSTARTED', 'STARTED', 'COMPLETED',
            'CANCELED', 'UNKNOWN'
        )
    ),
    UNIQUE (deliverable_id, plan_version_id)
);

CREATE TABLE delivery_acceptance_criteria (
    id UUID PRIMARY KEY,
    deliverable_version_id UUID NOT NULL REFERENCES delivery_deliverable_versions(id),
    sequence INTEGER NOT NULL CHECK (sequence > 0),
    statement TEXT NOT NULL,
    validation_method TEXT NOT NULL,
    expected_result TEXT NOT NULL,
    mandatory BOOLEAN NOT NULL,
    UNIQUE (deliverable_version_id, sequence)
);

CREATE TABLE delivery_dependencies (
    id UUID PRIMARY KEY,
    deliverable_version_id UUID NOT NULL REFERENCES delivery_deliverable_versions(id),
    dependency_type VARCHAR(24) NOT NULL CHECK (
        dependency_type IN ('INTERNAL', 'LINEAR', 'EXTERNAL')
    ),
    depends_on_deliverable_id UUID REFERENCES delivery_deliverables(id),
    description TEXT NOT NULL,
    owner_subject VARCHAR(255) NOT NULL,
    target_resolution_date DATE NOT NULL,
    blocking BOOLEAN NOT NULL
);

CREATE TABLE delivery_employee_assignments (
    id UUID PRIMARY KEY,
    deliverable_version_id UUID NOT NULL REFERENCES delivery_deliverable_versions(id),
    employee_id UUID NOT NULL REFERENCES employees(id),
    effective_from DATE NOT NULL,
    effective_to DATE,
    exception_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_delivery_assignment_dates CHECK (
        effective_to IS NULL OR effective_to >= effective_from
    ),
    UNIQUE (deliverable_version_id, employee_id, effective_from)
);

CREATE TABLE delivery_plan_approvals (
    id UUID PRIMARY KEY,
    plan_version_id UUID NOT NULL REFERENCES delivery_plan_versions(id),
    approver_subject VARCHAR(255) NOT NULL,
    decision VARCHAR(16) NOT NULL CHECK (decision IN ('APPROVE', 'REJECT')),
    signed_checksum VARCHAR(64) NOT NULL,
    authority_snapshot JSONB NOT NULL,
    comment TEXT,
    decided_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (plan_version_id, approver_subject)
);

CREATE TABLE delivery_plan_baselines (
    id UUID PRIMARY KEY,
    plan_version_id UUID NOT NULL UNIQUE REFERENCES delivery_plan_versions(id),
    checksum VARCHAR(64) NOT NULL,
    deliverable_count INTEGER NOT NULL CHECK (deliverable_count >= 0),
    original_baseline_id UUID REFERENCES delivery_plan_baselines(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE commitment_outbox (
    id UUID PRIMARY KEY,
    plan_version_id UUID NOT NULL REFERENCES delivery_plan_versions(id),
    baseline_id UUID NOT NULL REFERENCES delivery_plan_baselines(id),
    message_type VARCHAR(24) NOT NULL CHECK (message_type IN ('INITIAL', 'REVISION')),
    idempotency_key VARCHAR(160) NOT NULL UNIQUE,
    recipient_snapshot JSONB NOT NULL,
    subject_text TEXT NOT NULL,
    plain_text TEXT NOT NULL,
    html_text TEXT NOT NULL,
    archive_reference VARCHAR(512) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING' CHECK (
        status IN ('PENDING', 'SENT', 'RETRY', 'DEAD_LETTER')
    ),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE commitment_outbox_attempts (
    id UUID PRIMARY KEY,
    outbox_id UUID NOT NULL REFERENCES commitment_outbox(id),
    attempt_number INTEGER NOT NULL CHECK (attempt_number > 0),
    status VARCHAR(24) NOT NULL CHECK (
        status IN ('FAILED', 'SENT', 'DEAD_LETTER')
    ),
    provider_message_reference VARCHAR(255),
    error_code VARCHAR(64),
    attempted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (outbox_id, attempt_number)
);

CREATE TABLE delivery_audit_events (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL REFERENCES delivery_plans(id),
    plan_version_id UUID REFERENCES delivery_plan_versions(id),
    event_type VARCHAR(64) NOT NULL,
    actor_subject VARCHAR(255) NOT NULL,
    facts JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE linear_connections (
    id UUID PRIMARY KEY,
    engagement_id UUID NOT NULL REFERENCES engagements(id),
    provider_organization_id VARCHAR(128) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (
        status IN ('NOT_CONFIGURED', 'CONNECTED', 'ACTION_REQUIRED')
    ),
    provider_registration_status VARCHAR(32) NOT NULL DEFAULT 'EXTERNALLY_BLOCKED'
        CHECK (provider_registration_status IN (
            'EXTERNALLY_BLOCKED', 'NOT_CONFIGURED', 'CONFIGURED'
        )),
    credential_secret_ref VARCHAR(512),
    webhook_secret_ref VARCHAR(512),
    mapping_version INTEGER NOT NULL DEFAULT 1 CHECK (mapping_version > 0),
    last_verified_delivery_at TIMESTAMPTZ,
    last_reconciled_at TIMESTAMPTZ,
    last_error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (engagement_id, provider_organization_id)
);

CREATE TABLE linear_state_mappings (
    connection_id UUID NOT NULL REFERENCES linear_connections(id),
    mapping_version INTEGER NOT NULL CHECK (mapping_version > 0),
    provider_state_type VARCHAR(64) NOT NULL,
    provider_state_category VARCHAR(64) NOT NULL DEFAULT '',
    normalized_state VARCHAR(24) NOT NULL CHECK (
        normalized_state IN (
            'BACKLOG', 'UNSTARTED', 'STARTED', 'COMPLETED',
            'CANCELED', 'UNKNOWN'
        )
    ),
    PRIMARY KEY (
        connection_id, mapping_version, provider_state_type,
        provider_state_category
    )
);

CREATE TABLE linear_issue_links (
    id UUID PRIMARY KEY,
    deliverable_version_id UUID NOT NULL REFERENCES delivery_deliverable_versions(id),
    connection_id UUID NOT NULL REFERENCES linear_connections(id),
    linear_issue_uuid UUID NOT NULL,
    identifier VARCHAR(64) NOT NULL,
    issue_url VARCHAR(512) NOT NULL,
    multi_link_rationale TEXT,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'BROKEN', 'INACCESSIBLE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_subject VARCHAR(255) NOT NULL,
    UNIQUE (deliverable_version_id, linear_issue_uuid)
);

CREATE TABLE linear_issue_current (
    connection_id UUID NOT NULL REFERENCES linear_connections(id),
    linear_issue_uuid UUID NOT NULL,
    identifier VARCHAR(64) NOT NULL,
    issue_url VARCHAR(512) NOT NULL,
    title VARCHAR(512) NOT NULL,
    provider_state_id VARCHAR(128),
    provider_state_name VARCHAR(128),
    provider_state_type VARCHAR(64),
    provider_state_category VARCHAR(64),
    normalized_state VARCHAR(24) NOT NULL CHECK (
        normalized_state IN (
            'BACKLOG', 'UNSTARTED', 'STARTED', 'COMPLETED',
            'CANCELED', 'UNKNOWN'
        )
    ),
    provider_updated_at TIMESTAMPTZ,
    fetched_at TIMESTAMPTZ NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    stale BOOLEAN NOT NULL DEFAULT FALSE,
    inaccessible BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (connection_id, linear_issue_uuid)
);

CREATE TABLE linear_issue_events (
    id UUID PRIMARY KEY,
    connection_id UUID NOT NULL REFERENCES linear_connections(id),
    linear_issue_uuid UUID NOT NULL,
    delivery_id UUID,
    event_fingerprint VARCHAR(64) NOT NULL UNIQUE,
    event_action VARCHAR(32) NOT NULL,
    normalized_state VARCHAR(24) NOT NULL,
    provider_state JSONB NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE linear_issue_snapshots (
    id UUID PRIMARY KEY,
    issue_link_id UUID NOT NULL REFERENCES linear_issue_links(id),
    plan_version_id UUID REFERENCES delivery_plan_versions(id),
    snapshot_type VARCHAR(32) NOT NULL CHECK (
        snapshot_type IN (
            'PLAN_TIME', 'MONTH_END', 'HISTORICAL_RETRIEVAL'
        )
    ),
    status VARCHAR(24) NOT NULL CHECK (
        status IN ('CAPTURED', 'FETCH_FAILED', 'UNAVAILABLE')
    ),
    provider_state JSONB NOT NULL DEFAULT '{}'::jsonb,
    normalized_state VARCHAR(24),
    fetched_at TIMESTAMPTZ,
    payload_hash VARCHAR(64),
    confidence VARCHAR(32) NOT NULL CHECK (
        confidence IN (
            'SOURCE_EVENT_HISTORY', 'SOURCE_EXPORT',
            'CURRENT_STATE_ONLY', 'UNAVAILABLE'
        )
    ),
    failure_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (issue_link_id, plan_version_id, snapshot_type)
);

CREATE TABLE linear_webhook_deliveries (
    delivery_id UUID PRIMARY KEY,
    connection_id UUID NOT NULL REFERENCES linear_connections(id),
    event_fingerprint VARCHAR(64) NOT NULL UNIQUE,
    signature_verified_at TIMESTAMPTZ NOT NULL,
    provider_timestamp TIMESTAMPTZ NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    raw_payload JSONB NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE linear_webhook_queue (
    id UUID PRIMARY KEY,
    delivery_id UUID NOT NULL UNIQUE REFERENCES linear_webhook_deliveries(delivery_id),
    status VARCHAR(24) NOT NULL DEFAULT 'QUEUED' CHECK (
        status IN ('QUEUED', 'PROCESSING', 'PROCESSED', 'QUARANTINED', 'DEAD_LETTER')
    ),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    last_error_code VARCHAR(64),
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE linear_sync_jobs (
    id UUID PRIMARY KEY,
    connection_id UUID NOT NULL REFERENCES linear_connections(id),
    job_type VARCHAR(32) NOT NULL CHECK (
        job_type IN (
            'MANUAL_REFRESH', 'DELTA', 'NIGHTLY_RECONCILIATION',
            'PLAN_FREEZE', 'MONTH_END', 'POST_CLOSE'
        )
    ),
    status VARCHAR(24) NOT NULL CHECK (
        status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'DEAD_LETTER')
    ),
    checkpoint TEXT,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ
);

CREATE OR REPLACE FUNCTION enforce_delivery_project_engagement()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM delivery_plan_versions version
        JOIN delivery_plans plan ON plan.id = version.plan_id
        JOIN engagement_months month ON month.id = plan.engagement_month_id
        JOIN projects project ON project.id = NEW.project_id
        WHERE version.id = NEW.plan_version_id
          AND project.engagement_id = month.engagement_id
    ) THEN
        RAISE EXCEPTION 'Deliverable project is outside the plan engagement'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER delivery_project_engagement_gate
BEFORE INSERT OR UPDATE OF plan_version_id, project_id
ON delivery_deliverable_versions
FOR EACH ROW EXECUTE FUNCTION enforce_delivery_project_engagement();

CREATE OR REPLACE FUNCTION enforce_linear_link_engagement()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM delivery_deliverable_versions deliverable
        JOIN delivery_plan_versions version ON version.id = deliverable.plan_version_id
        JOIN delivery_plans plan ON plan.id = version.plan_id
        JOIN engagement_months month ON month.id = plan.engagement_month_id
        JOIN linear_connections connection ON connection.id = NEW.connection_id
        WHERE deliverable.id = NEW.deliverable_version_id
          AND connection.engagement_id = month.engagement_id
    ) THEN
        RAISE EXCEPTION 'Linear connection is outside the deliverable engagement'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER linear_link_engagement_gate
BEFORE INSERT OR UPDATE OF deliverable_version_id, connection_id
ON linear_issue_links
FOR EACH ROW EXECUTE FUNCTION enforce_linear_link_engagement();

CREATE TRIGGER delivery_plan_approvals_immutable
BEFORE UPDATE OR DELETE ON delivery_plan_approvals
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TRIGGER delivery_plan_baselines_immutable
BEFORE UPDATE OR DELETE ON delivery_plan_baselines
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TRIGGER commitment_outbox_immutable
BEFORE UPDATE OR DELETE ON commitment_outbox
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TRIGGER delivery_audit_events_immutable
BEFORE UPDATE OR DELETE ON delivery_audit_events
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TRIGGER linear_issue_events_immutable
BEFORE UPDATE OR DELETE ON linear_issue_events
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TRIGGER linear_issue_snapshots_immutable
BEFORE UPDATE OR DELETE ON linear_issue_snapshots
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TRIGGER linear_webhook_deliveries_immutable
BEFORE UPDATE OR DELETE ON linear_webhook_deliveries
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

INSERT INTO permissions (id, code, description) VALUES
    ('10000000-0000-0000-0000-000000000020', 'delivery.plan.read', 'Read delivery plans in an authorized scope'),
    ('10000000-0000-0000-0000-000000000021', 'delivery.plan.manage', 'Create and revise delivery plans'),
    ('10000000-0000-0000-0000-000000000022', 'delivery.plan.submit', 'Submit complete delivery plans'),
    ('10000000-0000-0000-0000-000000000023', 'delivery.plan.approve', 'Approve delivery plans in an authorized scope'),
    ('10000000-0000-0000-0000-000000000024', 'linear.integration.read', 'Read Linear evidence and integration health'),
    ('10000000-0000-0000-0000-000000000025', 'linear.integration.manage', 'Manage local Linear issue links'),
    ('10000000-0000-0000-0000-000000000026', 'linear.integration.replay', 'Replay durable Linear webhook work');

INSERT INTO role_permissions (role_id, permission_id)
SELECT role.id, permission.id
FROM roles role
CROSS JOIN permissions permission
WHERE
    (role.code IN ('ORG_ADMIN', 'ENGAGEMENT_ADMIN')
      AND permission.code IN (
        'delivery.plan.read', 'delivery.plan.manage', 'delivery.plan.submit',
        'delivery.plan.approve', 'linear.integration.read',
        'linear.integration.manage', 'linear.integration.replay'
      ))
    OR (role.code = 'VENDOR_MANAGER'
      AND permission.code IN (
        'delivery.plan.read', 'delivery.plan.manage', 'delivery.plan.submit',
        'linear.integration.read', 'linear.integration.manage'
      ))
    OR (role.code = 'CLIENT_PRODUCT_OWNER'
      AND permission.code IN (
        'delivery.plan.read', 'delivery.plan.approve',
        'linear.integration.read'
      ))
    OR (role.code = 'AUDITOR_READONLY'
      AND permission.code IN (
        'delivery.plan.read', 'linear.integration.read'
      ))
ON CONFLICT DO NOTHING;
