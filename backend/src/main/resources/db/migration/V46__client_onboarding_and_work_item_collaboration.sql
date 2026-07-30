-- Role-aware client onboarding and collaborative work-item execution.

INSERT INTO permissions(id, code, description) VALUES
    ('10000000-0000-0000-0000-000000000110', 'client.onboard', 'Onboard client organizations and engagements'),
    ('10000000-0000-0000-0000-000000000111', 'client.user.manage', 'Manage client users and scoped role grants'),
    ('10000000-0000-0000-0000-000000000112', 'workitem.read', 'Read scoped collaborative work items'),
    ('10000000-0000-0000-0000-000000000113', 'workitem.create', 'Create scoped collaborative work items'),
    ('10000000-0000-0000-0000-000000000114', 'workitem.update', 'Update scoped collaborative work items'),
    ('10000000-0000-0000-0000-000000000115', 'workitem.assign', 'Assign or transfer scoped work items'),
    ('10000000-0000-0000-0000-000000000116', 'workitem.comment', 'Comment on and mention users in scoped work items'),
    ('10000000-0000-0000-0000-000000000117', 'workitem.estimate', 'Record scoped work-item estimates'),
    ('10000000-0000-0000-0000-000000000118', 'workitem.effort', 'Record scoped work-item actual effort'),
    ('10000000-0000-0000-0000-000000000119', 'workitem.plan.approve', 'Record L1 plan approval and stack rank'),
    ('10000000-0000-0000-0000-000000000120', 'workitem.delivery.update', 'Update work-item delivery status'),
    ('10000000-0000-0000-0000-000000000121', 'workitem.delivery.approve.l1', 'Record L1 work-item delivery approval'),
    ('10000000-0000-0000-0000-000000000122', 'workitem.delivery.approve.l2', 'Record L2 work-item delivery approval'),
    ('10000000-0000-0000-0000-000000000123', 'workitem.bulk.import', 'Bulk-create scoped work items')
ON CONFLICT (code) DO NOTHING;

INSERT INTO roles(id, code, name, description) VALUES
    ('11000000-0000-0000-0000-000000000020', 'DEVELOPER', 'Developer', 'ArrowFoundry software delivery practitioner'),
    ('11000000-0000-0000-0000-000000000021', 'QA', 'Quality analyst', 'ArrowFoundry quality engineering practitioner'),
    ('11000000-0000-0000-0000-000000000022', 'PRODUCT_MANAGER', 'Product manager', 'ArrowFoundry product management practitioner'),
    ('11000000-0000-0000-0000-000000000023', 'PROGRAM_MANAGER', 'Program manager', 'ArrowFoundry program management practitioner'),
    ('11000000-0000-0000-0000-000000000024', 'UX_DESIGNER', 'UX designer', 'ArrowFoundry experience design practitioner'),
    ('11000000-0000-0000-0000-000000000025', 'DEVOPS', 'DevOps engineer', 'ArrowFoundry platform and delivery practitioner'),
    ('11000000-0000-0000-0000-000000000026', 'DATA_ANALYST', 'Data analyst', 'ArrowFoundry analytics practitioner')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions(role_id, permission_id)
SELECT role.id, permission.id
FROM roles role
CROSS JOIN permissions permission
WHERE (
    role.code IN ('ORG_ADMIN', 'ENGAGEMENT_ADMIN')
    AND permission.code IN (
        'client.onboard', 'client.user.manage', 'workitem.read',
        'workitem.create', 'workitem.update', 'workitem.assign',
        'workitem.comment', 'workitem.estimate', 'workitem.effort',
        'workitem.plan.approve', 'workitem.delivery.update',
        'workitem.delivery.approve.l1', 'workitem.delivery.approve.l2',
        'workitem.bulk.import')
) OR (
    role.code = 'VENDOR_MANAGER'
    AND permission.code IN (
        'client.onboard', 'client.user.manage',
        'workitem.read', 'workitem.create', 'workitem.update',
        'workitem.assign', 'workitem.comment', 'workitem.estimate',
        'workitem.effort', 'workitem.delivery.update',
        'workitem.bulk.import')
) OR (
    role.code = 'CLIENT_PRODUCT_OWNER'
    AND permission.code IN (
        'workitem.read', 'workitem.create', 'workitem.update',
        'workitem.comment', 'workitem.plan.approve',
        'workitem.delivery.approve.l1')
) OR (
    role.code = 'CLIENT_APPROVER'
    AND permission.code IN (
        'workitem.read', 'workitem.comment', 'workitem.plan.approve',
        'workitem.delivery.approve.l1', 'workitem.delivery.approve.l2')
) OR (
    role.code IN (
        'EMPLOYEE', 'DEVELOPER', 'QA', 'PRODUCT_MANAGER',
        'PROGRAM_MANAGER', 'UX_DESIGNER', 'DEVOPS', 'DATA_ANALYST')
    AND permission.code IN (
        'catalog.read', 'workitem.read', 'workitem.update',
        'workitem.assign', 'workitem.comment', 'workitem.estimate',
        'workitem.effort', 'workitem.delivery.update')
) OR (
    role.code = 'PROCUREMENT_REVIEWER'
    AND permission.code = 'workitem.read'
)
ON CONFLICT DO NOTHING;

CREATE TABLE work_items (
    id UUID PRIMARY KEY,
    engagement_id UUID NOT NULL REFERENCES engagements(id),
    project_id UUID NOT NULL REFERENCES projects(id),
    engagement_month_id UUID REFERENCES engagement_months(id),
    work_item_code VARCHAR(64) NOT NULL,
    title VARCHAR(256) NOT NULL CHECK (btrim(title) <> ''),
    description TEXT NOT NULL CHECK (btrim(description) <> ''),
    workflow_description TEXT NOT NULL DEFAULT '',
    acceptance_criteria TEXT NOT NULL DEFAULT '',
    priority VARCHAR(8) NOT NULL CHECK (priority IN ('P0', 'P1', 'P2', 'P3')),
    stack_rank INTEGER CHECK (stack_rank IS NULL OR stack_rank > 0),
    lifecycle_status VARCHAR(32) NOT NULL DEFAULT 'BACKLOG' CHECK (
        lifecycle_status IN (
            'BACKLOG', 'PLANNED', 'APPROVED', 'IN_PROGRESS', 'BLOCKED',
            'DELIVERED', 'PARTIALLY_DELIVERED', 'NOT_DELIVERED', 'CANCELLED')),
    delivery_summary TEXT,
    created_on_behalf_of_client BOOLEAN NOT NULL DEFAULT FALSE,
    created_by_subject VARCHAR(255) NOT NULL,
    updated_by_subject VARCHAR(255) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (engagement_id, work_item_code)
);
CREATE INDEX idx_work_items_scope
    ON work_items(engagement_id, engagement_month_id, lifecycle_status);

CREATE OR REPLACE FUNCTION work_item_scope_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM projects project
        WHERE project.id = NEW.project_id
          AND project.engagement_id = NEW.engagement_id
    ) THEN
        RAISE EXCEPTION 'Work-item project must belong to the engagement'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.engagement_month_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM engagement_months month
        WHERE month.id = NEW.engagement_month_id
          AND month.engagement_id = NEW.engagement_id
    ) THEN
        RAISE EXCEPTION 'Work-item month must belong to the engagement'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER work_item_scope_gate
BEFORE INSERT OR UPDATE OF engagement_id, project_id, engagement_month_id
ON work_items FOR EACH ROW EXECUTE FUNCTION work_item_scope_guard();

CREATE TABLE work_item_links (
    id UUID PRIMARY KEY,
    work_item_id UUID NOT NULL REFERENCES work_items(id) ON DELETE CASCADE,
    link_type VARCHAR(32) NOT NULL CHECK (link_type IN (
        'DOCUMENT', 'PRD', 'USER_STORY', 'FIGMA', 'PROTOTYPE',
        'LINEAR', 'JIRA', 'CODE_REVIEW', 'COMMIT', 'TEST_CASES',
        'TEST_RUN', 'OTHER')),
    label VARCHAR(160) NOT NULL,
    url TEXT NOT NULL CHECK (url ~ '^https://'),
    created_by_subject VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (work_item_id, link_type, url)
);

CREATE TABLE work_item_assignments (
    id UUID PRIMARY KEY,
    work_item_id UUID NOT NULL REFERENCES work_items(id) ON DELETE CASCADE,
    user_profile_id UUID NOT NULL REFERENCES user_profiles(id),
    discipline VARCHAR(32) NOT NULL CHECK (discipline IN (
        'DEVELOPER', 'QA', 'PRODUCT_MANAGER', 'PROGRAM_MANAGER',
        'UX_DESIGNER', 'DEVOPS', 'DATA_ANALYST', 'OTHER')),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'ENDED')),
    assigned_by_subject VARCHAR(255) NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at TIMESTAMPTZ,
    UNIQUE (work_item_id, user_profile_id, discipline, status)
);
CREATE INDEX idx_work_item_assignment_user
    ON work_item_assignments(user_profile_id, status, work_item_id);

CREATE TABLE work_item_comments (
    id UUID PRIMARY KEY,
    work_item_id UUID NOT NULL REFERENCES work_items(id) ON DELETE CASCADE,
    body TEXT NOT NULL CHECK (btrim(body) <> '' AND length(body) <= 10000),
    author_subject VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE work_item_mentions (
    comment_id UUID NOT NULL REFERENCES work_item_comments(id) ON DELETE CASCADE,
    user_profile_id UUID NOT NULL REFERENCES user_profiles(id),
    mentioned_by_subject VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (comment_id, user_profile_id)
);
CREATE INDEX idx_work_item_mentions_user
    ON work_item_mentions(user_profile_id, created_at DESC);

CREATE TABLE work_item_estimates (
    id UUID PRIMARY KEY,
    work_item_id UUID NOT NULL REFERENCES work_items(id) ON DELETE CASCADE,
    user_profile_id UUID NOT NULL REFERENCES user_profiles(id),
    hours NUMERIC(9,2) NOT NULL CHECK (hours > 0 AND hours <= 100000),
    note TEXT,
    created_by_subject VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ,
    deleted_by_subject VARCHAR(255)
);

CREATE TABLE work_item_efforts (
    id UUID PRIMARY KEY,
    work_item_id UUID NOT NULL REFERENCES work_items(id) ON DELETE CASCADE,
    user_profile_id UUID NOT NULL REFERENCES user_profiles(id),
    work_date DATE NOT NULL,
    hours NUMERIC(9,2) NOT NULL CHECK (hours > 0 AND hours <= 24),
    note TEXT,
    created_by_subject VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE work_item_approvals (
    id UUID PRIMARY KEY,
    work_item_id UUID NOT NULL REFERENCES work_items(id) ON DELETE CASCADE,
    stage VARCHAR(24) NOT NULL CHECK (
        stage IN ('PLAN_L1', 'DELIVERY_L1', 'DELIVERY_L2')),
    decision VARCHAR(16) NOT NULL CHECK (
        decision IN ('APPROVED', 'REJECTED', 'CHANGES_REQUESTED')),
    stack_rank INTEGER CHECK (
        stage <> 'PLAN_L1' OR (stack_rank IS NOT NULL AND stack_rank > 0)),
    comment TEXT,
    actor_subject VARCHAR(255) NOT NULL,
    work_item_version BIGINT NOT NULL,
    decided_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (work_item_id, stage, actor_subject, work_item_version)
);

CREATE TABLE work_item_audit_events (
    id UUID PRIMARY KEY,
    work_item_id UUID REFERENCES work_items(id),
    engagement_id UUID NOT NULL REFERENCES engagements(id),
    event_type VARCHAR(80) NOT NULL,
    actor_subject VARCHAR(255) NOT NULL,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TRIGGER work_item_audit_immutable
BEFORE UPDATE OR DELETE ON work_item_audit_events
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
