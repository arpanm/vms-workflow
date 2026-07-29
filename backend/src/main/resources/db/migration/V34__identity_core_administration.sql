-- F01 completion: versioned core administration and controlled month history.
-- Existing delivery/certification services keep their specialized policy
-- stores. These records are the shared administrative source of truth for new
-- workflows and never rewrite evidence already captured by F03-F06.

INSERT INTO permissions(id, code, description) VALUES
    ('10000000-0000-0000-0000-000000000002',
     'engagement.update', 'Update authorized engagement master data'),
    ('10000000-0000-0000-0000-000000000003',
     'engagement.configure', 'Publish effective-dated engagement configuration'),
    ('10000000-0000-0000-0000-000000000004',
     'contacts.manage', 'Manage authorized engagement contact groups'),
    ('10000000-0000-0000-0000-000000000005',
     'approval.policy.manage', 'Manage authorized approval policy versions'),
    ('10000000-0000-0000-0000-000000000006',
     'delegation.manage', 'Manage eligible effective-dated delegations'),
    ('10000000-0000-0000-0000-000000000007',
     'month.transition', 'Perform governed engagement-month transitions'),
    ('10000000-0000-0000-0000-000000000008',
     'approval.request.create', 'Create governed approval requests'),
    ('10000000-0000-0000-0000-000000000009',
     'approval.request.act', 'Act on an eligible approval policy stage')
ON CONFLICT (code) DO NOTHING;

INSERT INTO roles(id, code, name, description) VALUES
    ('11000000-0000-0000-0000-000000000013',
     'CLIENT_APPROVER', 'Client approver',
     'Scoped client business approval authority'),
    ('11000000-0000-0000-0000-000000000014',
     'PROGRAM_GOVERNANCE', 'Program governance',
     'Scoped program monitoring, confirmation and reopen authority'),
    ('11000000-0000-0000-0000-000000000015',
     'INTEGRATION_ADMIN', 'Integration administrator',
     'Scoped integration configuration, health and replay authority'),
    ('11000000-0000-0000-0000-000000000016',
     'SUPPORT_OPERATOR', 'Support operator',
     'Platform support role without implicit business approval authority'),
    ('11000000-0000-0000-0000-000000000017',
     'SERVICE_ACCOUNT', 'Service account',
     'Non-human role whose permissions must be assigned explicitly')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions(role_id, permission_id)
SELECT role.id, permission.id
FROM roles role
CROSS JOIN permissions permission
WHERE role.code IN ('ORG_ADMIN', 'ENGAGEMENT_ADMIN')
  AND permission.code IN (
      'engagement.update', 'engagement.configure', 'contacts.manage',
      'approval.policy.manage', 'delegation.manage', 'month.transition',
      'approval.request.create', 'approval.request.act')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id, permission_id)
SELECT role.id, permission.id
FROM roles role
CROSS JOIN permissions permission
WHERE role.code = 'CLIENT_PRODUCT_OWNER'
  AND permission.code IN ('delegation.manage', 'approval.request.act')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id, permission_id)
SELECT role.id, permission.id
FROM roles role
CROSS JOIN permissions permission
WHERE (
    role.code = 'CLIENT_APPROVER'
    AND permission.code IN (
        'catalog.read', 'delivery.plan.read', 'delivery.plan.approve',
        'certification.read', 'certification.item.decide',
        'certification.confirmation.act', 'certification.reopen.request',
        'approval.request.act')
) OR (
    role.code = 'PROGRAM_GOVERNANCE'
    AND permission.code IN (
        'catalog.read', 'certification.read',
        'certification.summary.create',
        'certification.confirmation.act',
        'certification.reopen.request', 'certification.reopen.approve',
        'approval.request.act')
) OR (
    role.code = 'PROCUREMENT_REVIEWER'
    AND permission.code = 'approval.request.act'
) OR (
    role.code = 'INTEGRATION_ADMIN'
    AND permission.code IN (
        'catalog.read', 'linear.integration.read',
        'linear.integration.manage', 'linear.integration.replay')
) OR (
    role.code = 'SUPPORT_OPERATOR'
    AND permission.code = 'feature.flag.read'
)
ON CONFLICT DO NOTHING;

ALTER TABLE engagements
    ADD COLUMN default_project_id UUID REFERENCES projects(id),
    ADD COLUMN admin_version BIGINT NOT NULL DEFAULT 0;

UPDATE engagements
SET default_project_id = (
    SELECT project.id
    FROM projects project
    WHERE project.engagement_id = engagements.id
    ORDER BY project.project_code
    LIMIT 1
)
WHERE default_project_id IS NULL;

CREATE OR REPLACE FUNCTION f01_engagement_default_project_scope_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF NEW.default_project_id IS NOT NULL
       AND NOT EXISTS (
           SELECT 1 FROM projects project
           WHERE project.id = NEW.default_project_id
             AND project.engagement_id = NEW.id
       ) THEN
        RAISE EXCEPTION 'Default project must belong to the engagement'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER f01_engagement_default_project_scope_gate
BEFORE INSERT OR UPDATE OF default_project_id, id ON engagements
FOR EACH ROW EXECUTE FUNCTION f01_engagement_default_project_scope_guard();

CREATE TABLE engagement_configuration_versions (
    id UUID PRIMARY KEY,
    engagement_id UUID NOT NULL REFERENCES engagements(id),
    version INTEGER NOT NULL CHECK (version > 0),
    status VARCHAR(16) NOT NULL
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'SUPERSEDED')),
    valid_from DATE NOT NULL,
    valid_to DATE,
    timezone VARCHAR(64) NOT NULL,
    planning_due_day SMALLINT CHECK (planning_due_day BETWEEN 1 AND 28),
    certification_due_day SMALLINT
        CHECK (certification_due_day BETWEEN 1 AND 28),
    confirmation_due_day SMALLINT
        CHECK (confirmation_due_day BETWEEN 1 AND 28),
    reopen_policy JSONB NOT NULL DEFAULT '{}'::jsonb
        CHECK (jsonb_typeof(reopen_policy) = 'object'),
    notification_policy JSONB NOT NULL DEFAULT '{}'::jsonb
        CHECK (jsonb_typeof(notification_policy) = 'object'),
    created_by_subject VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    supersedes_id UUID REFERENCES engagement_configuration_versions(id),
    UNIQUE (engagement_id, version),
    CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CHECK (
        (status IN ('PUBLISHED', 'SUPERSEDED')
            AND published_at IS NOT NULL)
        OR (status = 'DRAFT' AND published_at IS NULL)
    )
);

CREATE UNIQUE INDEX uq_f01_published_config_effective_date
    ON engagement_configuration_versions(engagement_id, valid_from)
    WHERE status = 'PUBLISHED';

ALTER TABLE engagements
    ADD COLUMN configuration_version_id UUID
        REFERENCES engagement_configuration_versions(id);

CREATE OR REPLACE FUNCTION f01_configuration_scope_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF NEW.configuration_version_id IS NOT NULL
       AND NOT EXISTS (
           SELECT 1
           FROM engagement_configuration_versions configuration
           WHERE configuration.id = NEW.configuration_version_id
             AND configuration.engagement_id = NEW.id
             AND configuration.status = 'PUBLISHED'
       ) THEN
        RAISE EXCEPTION
            'Current configuration must be a published version of its engagement'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER f01_configuration_scope_gate
BEFORE INSERT OR UPDATE OF configuration_version_id, id ON engagements
FOR EACH ROW EXECUTE FUNCTION f01_configuration_scope_guard();

CREATE OR REPLACE FUNCTION f01_configuration_version_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status = 'PUBLISHED' THEN
            RAISE EXCEPTION 'Published configuration versions are immutable'
                USING ERRCODE = '55000';
        END IF;
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.status = 'PUBLISHED' THEN
        RAISE EXCEPTION 'Published configuration versions are immutable'
            USING ERRCODE = '55000';
    END IF;
    -- An open-ended earlier version is implicitly bounded by the next
    -- effective date. Explicit finite windows may never cover another
    -- version's start, and a back-dated open-ended version may never cover an
    -- existing later start.
    IF NEW.status = 'PUBLISHED' AND EXISTS (
        SELECT 1
        FROM engagement_configuration_versions existing
        WHERE existing.engagement_id = NEW.engagement_id
          AND existing.id <> NEW.id
          AND existing.status = 'PUBLISHED'
          AND (
              existing.valid_from = NEW.valid_from
              OR
              (
                  existing.valid_from < NEW.valid_from
                  AND existing.valid_to IS NOT NULL
                  AND existing.valid_to >= NEW.valid_from
              )
              OR (
                  existing.valid_from > NEW.valid_from
                  AND (
                      NEW.valid_to IS NULL
                      OR NEW.valid_to >= existing.valid_from
                  )
              )
          )
    ) THEN
        RAISE EXCEPTION
            'Published engagement configuration effective windows cannot overlap'
            USING ERRCODE = '23P01';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER f01_configuration_version_integrity_gate
BEFORE INSERT OR UPDATE OR DELETE ON engagement_configuration_versions
FOR EACH ROW EXECUTE FUNCTION f01_configuration_version_guard();

INSERT INTO engagement_configuration_versions(
    id, engagement_id, version, status, valid_from, timezone,
    reopen_policy, notification_policy, created_by_subject, published_at)
SELECT gen_random_uuid(), engagement.id, 1, 'PUBLISHED',
       engagement.start_date, organization.default_timezone,
       '{"reasonRequired":true,"approvalRequired":true}'::jsonb,
       '{"recipientSnapshotRequired":true}'::jsonb,
       'SYSTEM:V34_MIGRATION', CURRENT_TIMESTAMP
FROM engagements engagement
JOIN organizations organization
  ON organization.id = engagement.client_organization_id;

UPDATE engagements engagement
SET configuration_version_id = configuration.id
FROM engagement_configuration_versions configuration
WHERE configuration.engagement_id = engagement.id
  AND configuration.version = 1;

CREATE TABLE contact_groups (
    id UUID PRIMARY KEY,
    engagement_id UUID NOT NULL REFERENCES engagements(id),
    project_id UUID REFERENCES projects(id),
    code VARCHAR(64) NOT NULL,
    name VARCHAR(160) NOT NULL,
    group_type VARCHAR(40) NOT NULL CHECK (group_type IN (
        'CLIENT_PRODUCT_OWNERS', 'CLIENT_APPROVERS',
        'VENDOR_DELIVERY', 'VENDOR_HR', 'VENDOR_FINANCE',
        'PROCUREMENT_CC', 'ESCALATION', 'AUDIT_OBSERVERS', 'OTHER'
    )),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'ARCHIVED')),
    version BIGINT NOT NULL DEFAULT 0,
    created_by_subject VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (engagement_id, code)
);

CREATE TABLE contact_group_members (
    id UUID PRIMARY KEY,
    contact_group_id UUID NOT NULL REFERENCES contact_groups(id),
    user_profile_id UUID REFERENCES user_profiles(id),
    email VARCHAR(320) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    role_attribution VARCHAR(80) NOT NULL,
    verified BOOLEAN NOT NULL DEFAULT FALSE,
    valid_from DATE NOT NULL,
    valid_to DATE,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'REVOKED')),
    created_by_subject VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (valid_to IS NULL OR valid_to >= valid_from),
    UNIQUE (contact_group_id, email, valid_from)
);

CREATE OR REPLACE FUNCTION f01_contact_member_scope_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
DECLARE
    group_engagement UUID;
BEGIN
    SELECT contact_group.engagement_id INTO group_engagement
    FROM contact_groups contact_group
    WHERE contact_group.id = NEW.contact_group_id;
    IF group_engagement IS NULL THEN
        RAISE EXCEPTION 'Contact group is unavailable'
            USING ERRCODE = '23503';
    END IF;
    IF NEW.user_profile_id IS NOT NULL AND NOT EXISTS (
        SELECT 1
        FROM user_profiles user_profile
        JOIN memberships membership
          ON membership.user_profile_id = user_profile.id
        JOIN engagements engagement ON engagement.id = group_engagement
        WHERE user_profile.id = NEW.user_profile_id
          AND user_profile.status = 'ACTIVE'
          AND membership.status = 'ACTIVE'
          AND membership.valid_from <= NEW.valid_from
          AND (
              membership.valid_to IS NULL
              OR membership.valid_to >= COALESCE(NEW.valid_to, NEW.valid_from)
          )
          AND membership.organization_id IN (
              engagement.client_organization_id,
              engagement.vendor_organization_id,
              engagement.procurement_organization_id)
    ) THEN
        RAISE EXCEPTION
            'Contact user must have effective membership in a participating organization'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER f01_contact_member_scope_gate
BEFORE INSERT OR UPDATE OF contact_group_id, user_profile_id,
    valid_from, valid_to ON contact_group_members
FOR EACH ROW EXECUTE FUNCTION f01_contact_member_scope_guard();

CREATE OR REPLACE FUNCTION f01_project_engagement_scope_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF NEW.project_id IS NOT NULL
       AND NOT EXISTS (
           SELECT 1 FROM projects project
           WHERE project.id = NEW.project_id
             AND project.engagement_id = NEW.engagement_id
       ) THEN
        RAISE EXCEPTION 'Project must belong to the engagement'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER f01_contact_group_project_scope_gate
BEFORE INSERT OR UPDATE OF engagement_id, project_id ON contact_groups
FOR EACH ROW EXECUTE FUNCTION f01_project_engagement_scope_guard();

CREATE TABLE approval_policies (
    id UUID PRIMARY KEY,
    engagement_id UUID NOT NULL REFERENCES engagements(id),
    project_id UUID REFERENCES projects(id),
    code VARCHAR(64) NOT NULL,
    name VARCHAR(160) NOT NULL,
    action_type VARCHAR(48) NOT NULL CHECK (action_type IN (
        'PLAN_APPROVAL', 'LEAVE_APPROVAL', 'REGULARIZATION',
        'ATTENDANCE_CORRECTION', 'DELIVERY_CERTIFICATION',
        'MONTH_CONFIRMATION', 'REOPEN', 'PROCUREMENT_EXCEPTION'
    )),
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    current_version_id UUID,
    version BIGINT NOT NULL DEFAULT 0,
    created_by_subject VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (engagement_id, code)
);

CREATE TRIGGER f01_approval_policy_project_scope_gate
BEFORE INSERT OR UPDATE OF engagement_id, project_id ON approval_policies
FOR EACH ROW EXECUTE FUNCTION f01_project_engagement_scope_guard();

CREATE TABLE approval_policy_versions (
    id UUID PRIMARY KEY,
    policy_id UUID NOT NULL REFERENCES approval_policies(id),
    version INTEGER NOT NULL CHECK (version > 0),
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'SUPERSEDED')),
    valid_from DATE NOT NULL,
    valid_to DATE,
    prohibit_self_approval BOOLEAN NOT NULL DEFAULT TRUE,
    evidence_required BOOLEAN NOT NULL DEFAULT TRUE,
    rules JSONB NOT NULL DEFAULT '{}'::jsonb
        CHECK (jsonb_typeof(rules) = 'object'),
    created_by_subject VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    supersedes_id UUID REFERENCES approval_policy_versions(id),
    UNIQUE (policy_id, version),
    CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CHECK (
        (status IN ('PUBLISHED', 'SUPERSEDED')
            AND published_at IS NOT NULL)
        OR (status = 'DRAFT' AND published_at IS NULL)
    )
);

ALTER TABLE approval_policies
    ADD CONSTRAINT fk_f01_approval_policy_current_version
    FOREIGN KEY (current_version_id) REFERENCES approval_policy_versions(id);

CREATE OR REPLACE FUNCTION f01_approval_policy_version_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status = 'PUBLISHED' THEN
            RAISE EXCEPTION 'Published approval policy versions are immutable'
                USING ERRCODE = '55000';
        END IF;
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.status = 'PUBLISHED' THEN
        IF NEW.status = 'PUBLISHED'
           AND NEW.valid_to IS NOT NULL
           AND (OLD.valid_to IS NULL OR NEW.valid_to < OLD.valid_to)
           AND ROW(
               OLD.id, OLD.policy_id, OLD.version, OLD.status,
               OLD.valid_from, OLD.prohibit_self_approval,
               OLD.evidence_required, OLD.rules, OLD.created_by_subject,
               OLD.created_at, OLD.published_at, OLD.supersedes_id
           ) IS NOT DISTINCT FROM ROW(
               NEW.id, NEW.policy_id, NEW.version, NEW.status,
               NEW.valid_from, NEW.prohibit_self_approval,
               NEW.evidence_required, NEW.rules, NEW.created_by_subject,
               NEW.created_at, NEW.published_at, NEW.supersedes_id
           ) THEN
            RETURN NEW;
        END IF;
        IF NEW.status = 'SUPERSEDED'
           AND ROW(
               OLD.id, OLD.policy_id, OLD.version, OLD.valid_from,
               OLD.valid_to, OLD.prohibit_self_approval,
               OLD.evidence_required, OLD.rules, OLD.created_by_subject,
               OLD.created_at, OLD.published_at, OLD.supersedes_id
           ) IS NOT DISTINCT FROM ROW(
               NEW.id, NEW.policy_id, NEW.version, NEW.valid_from,
               NEW.valid_to, NEW.prohibit_self_approval,
               NEW.evidence_required, NEW.rules, NEW.created_by_subject,
               NEW.created_at, NEW.published_at, NEW.supersedes_id
           ) THEN
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'Published approval policy versions are immutable'
            USING ERRCODE = '55000';
    END IF;
    IF TG_OP = 'UPDATE'
       AND OLD.status = 'DRAFT' AND NEW.status = 'PUBLISHED' THEN
        IF ROW(
            OLD.id, OLD.policy_id, OLD.version, OLD.valid_from, OLD.valid_to,
            OLD.prohibit_self_approval, OLD.evidence_required, OLD.rules,
            OLD.created_by_subject, OLD.created_at, OLD.supersedes_id
        ) IS DISTINCT FROM ROW(
            NEW.id, NEW.policy_id, NEW.version, NEW.valid_from, NEW.valid_to,
            NEW.prohibit_self_approval, NEW.evidence_required, NEW.rules,
            NEW.created_by_subject, NEW.created_at, NEW.supersedes_id
        ) THEN
            RAISE EXCEPTION
                'Policy publication may only change publication status and timestamp'
                USING ERRCODE = '55000';
        END IF;
    ELSIF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION
            'Draft policy versions are replaced, not edited'
            USING ERRCODE = '55000';
    END IF;
    IF NEW.status = 'PUBLISHED' AND EXISTS (
        SELECT 1
        FROM approval_policy_versions existing
        WHERE existing.policy_id = NEW.policy_id
          AND existing.id <> NEW.id
          AND existing.status = 'PUBLISHED'
          AND (
              existing.valid_from = NEW.valid_from
              OR
              (
                  existing.valid_from < NEW.valid_from
                  AND existing.valid_to IS NOT NULL
                  AND existing.valid_to >= NEW.valid_from
              )
              OR (
                  existing.valid_from > NEW.valid_from
                  AND (
                      NEW.valid_to IS NULL
                      OR NEW.valid_to >= existing.valid_from
                  )
              )
          )
    ) THEN
        RAISE EXCEPTION
            'Published approval policy effective windows cannot overlap'
            USING ERRCODE = '23P01';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER f01_approval_policy_version_integrity_gate
BEFORE INSERT OR UPDATE OR DELETE ON approval_policy_versions
FOR EACH ROW EXECUTE FUNCTION f01_approval_policy_version_guard();

CREATE OR REPLACE FUNCTION f01_approval_policy_current_scope_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF NEW.current_version_id IS NOT NULL
       AND NOT EXISTS (
           SELECT 1 FROM approval_policy_versions version
           WHERE version.id = NEW.current_version_id
             AND version.policy_id = NEW.id
       ) THEN
        RAISE EXCEPTION
            'Current approval version must belong to its policy'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER f01_approval_policy_current_scope_gate
BEFORE INSERT OR UPDATE OF current_version_id, id ON approval_policies
FOR EACH ROW EXECUTE FUNCTION f01_approval_policy_current_scope_guard();

CREATE TABLE approval_policy_stages (
    id UUID PRIMARY KEY,
    policy_version_id UUID NOT NULL REFERENCES approval_policy_versions(id),
    stage_order INTEGER NOT NULL CHECK (stage_order > 0),
    name VARCHAR(160) NOT NULL,
    role_code VARCHAR(64) REFERENCES roles(code),
    contact_group_id UUID REFERENCES contact_groups(id),
    explicit_assignee_id UUID REFERENCES user_profiles(id),
    permission_code VARCHAR(128) REFERENCES permissions(code),
    quorum_mode VARCHAR(16) NOT NULL
        CHECK (quorum_mode IN ('ANY_ONE', 'ALL', 'N_OF_M')),
    quorum_required INTEGER NOT NULL CHECK (quorum_required > 0),
    allow_delegation BOOLEAN NOT NULL DEFAULT TRUE,
    due_duration_hours INTEGER CHECK (due_duration_hours > 0),
    UNIQUE (policy_version_id, stage_order),
    CHECK (
        num_nonnulls(
            role_code, contact_group_id, explicit_assignee_id,
            permission_code
        ) = 1
    ),
    CHECK (
        (quorum_mode = 'ANY_ONE' AND quorum_required = 1)
        OR quorum_mode IN ('ALL', 'N_OF_M')
    )
);

CREATE OR REPLACE FUNCTION f01_approval_stage_scope_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
DECLARE
    policy_engagement UUID;
    policy_project UUID;
BEGIN
    SELECT policy.engagement_id, policy.project_id
      INTO policy_engagement, policy_project
    FROM approval_policy_versions version
    JOIN approval_policies policy ON policy.id = version.policy_id
    WHERE version.id = NEW.policy_version_id;
    IF policy_engagement IS NULL THEN
        RAISE EXCEPTION 'Approval policy version is unavailable'
            USING ERRCODE = '23503';
    END IF;
    IF NEW.contact_group_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM contact_groups contact_group
        WHERE contact_group.id = NEW.contact_group_id
          AND contact_group.engagement_id = policy_engagement
          AND contact_group.project_id IS NOT DISTINCT FROM policy_project
          AND contact_group.status = 'ACTIVE'
    ) THEN
        RAISE EXCEPTION
            'Approval stage contact group must belong to the policy engagement'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.explicit_assignee_id IS NOT NULL AND NOT EXISTS (
        SELECT 1
        FROM memberships membership
        JOIN engagements engagement ON engagement.id = policy_engagement
        JOIN user_profiles user_profile
          ON user_profile.id = membership.user_profile_id
        WHERE membership.user_profile_id = NEW.explicit_assignee_id
          AND membership.status = 'ACTIVE'
          AND user_profile.status = 'ACTIVE'
          AND membership.organization_id IN (
              engagement.client_organization_id,
              engagement.vendor_organization_id,
              engagement.procurement_organization_id)
    ) THEN
        RAISE EXCEPTION
            'Explicit approval assignee must belong to the policy engagement'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER f01_approval_stage_scope_gate
BEFORE INSERT OR UPDATE OF policy_version_id, contact_group_id,
    explicit_assignee_id, permission_code ON approval_policy_stages
FOR EACH ROW EXECUTE FUNCTION f01_approval_stage_scope_guard();

-- Existing F04 reopen endpoints remain API-compatible while their authority
-- is captured by the shared approval engine. The system policy is intentionally
-- one-stage: F04 already exposes one separated decision, and the stage snapshot
-- resolves every currently authorized certification reopen approver.
INSERT INTO approval_policies(
    id, engagement_id, code, name, action_type, status,
    version, created_by_subject)
SELECT gen_random_uuid(), engagement.id, 'SYSTEM_F04_REOPEN',
       'F04 governed reopen bridge', 'REOPEN', 'ACTIVE',
       1, 'SYSTEM:V34_MIGRATION'
FROM engagements engagement;

INSERT INTO approval_policy_versions(
    id, policy_id, version, status, valid_from,
    prohibit_self_approval, evidence_required, rules,
    created_by_subject, published_at)
SELECT gen_random_uuid(), policy.id, 1, 'PUBLISHED',
       engagement.start_date, TRUE, TRUE,
       '{"schema":"f04-core-reopen-bridge-v1",'
           '"legacyApiCompatible":true}'::jsonb,
       'SYSTEM:V34_MIGRATION', CURRENT_TIMESTAMP
FROM approval_policies policy
JOIN engagements engagement ON engagement.id = policy.engagement_id
WHERE policy.code = 'SYSTEM_F04_REOPEN';

UPDATE approval_policies policy
SET current_version_id = version.id
FROM approval_policy_versions version
WHERE version.policy_id = policy.id
  AND policy.code = 'SYSTEM_F04_REOPEN'
  AND version.version = 1;

INSERT INTO approval_policy_stages(
    id, policy_version_id, stage_order, name, permission_code,
    quorum_mode, quorum_required, allow_delegation)
SELECT gen_random_uuid(), version.id, 1,
       'Authorized F04 reopen approver',
       'certification.reopen.approve',
       'ANY_ONE', 1, FALSE
FROM approval_policy_versions version
JOIN approval_policies policy ON policy.id = version.policy_id
WHERE policy.code = 'SYSTEM_F04_REOPEN'
  AND version.version = 1;

CREATE TABLE delegations (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    engagement_id UUID REFERENCES engagements(id),
    project_id UUID REFERENCES projects(id),
    delegator_user_id UUID NOT NULL REFERENCES user_profiles(id),
    delegate_user_id UUID NOT NULL REFERENCES user_profiles(id),
    action_codes TEXT[] NOT NULL CHECK (cardinality(action_codes) > 0),
    valid_from TIMESTAMPTZ NOT NULL,
    valid_to TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'REVOKED', 'EXPIRED')),
    reason VARCHAR(500) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_by_subject VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_by_subject VARCHAR(255),
    revoked_at TIMESTAMPTZ,
    CHECK (delegator_user_id <> delegate_user_id),
    CHECK (valid_to > valid_from),
    CHECK (
        (status = 'REVOKED' AND revoked_at IS NOT NULL
            AND revoked_by_subject IS NOT NULL)
        OR (status <> 'REVOKED' AND revoked_at IS NULL
            AND revoked_by_subject IS NULL)
    )
);

CREATE OR REPLACE FUNCTION f01_delegation_scope_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF NEW.status = 'ACTIVE' AND NEW.valid_to <= CURRENT_TIMESTAMP THEN
        RAISE EXCEPTION 'An active delegation cannot already be expired'
            USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM memberships membership
        WHERE membership.organization_id = NEW.organization_id
          AND membership.user_profile_id = NEW.delegator_user_id
          AND membership.status = 'ACTIVE'
          AND membership.valid_from <= NEW.valid_from::date
          AND (
              membership.valid_to IS NULL
              OR membership.valid_to >= NEW.valid_to::date
          )
    ) OR NOT EXISTS (
        SELECT 1 FROM memberships membership
        WHERE membership.organization_id = NEW.organization_id
          AND membership.user_profile_id = NEW.delegate_user_id
          AND membership.status = 'ACTIVE'
          AND membership.valid_from <= NEW.valid_from::date
          AND (
              membership.valid_to IS NULL
              OR membership.valid_to >= NEW.valid_to::date
          )
    ) THEN
        RAISE EXCEPTION 'Delegation users must belong to the organization'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.engagement_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM engagements engagement
        WHERE engagement.id = NEW.engagement_id
          AND NEW.organization_id IN (
              engagement.client_organization_id,
              engagement.vendor_organization_id,
              engagement.procurement_organization_id)
    ) THEN
        RAISE EXCEPTION 'Delegation engagement must include the organization'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.project_id IS NOT NULL AND (
        NEW.engagement_id IS NULL OR NOT EXISTS (
            SELECT 1 FROM projects project
            WHERE project.id = NEW.project_id
              AND project.engagement_id = NEW.engagement_id
        )
    ) THEN
        RAISE EXCEPTION 'Delegation project must belong to its engagement'
            USING ERRCODE = '23514';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM unnest(NEW.action_codes) requested(action_code)
        WHERE requested.action_code IS NULL
           OR btrim(requested.action_code) = ''
           OR NOT EXISTS (
               SELECT 1
               FROM user_profiles user_profile
               JOIN role_assignments assignment
                 ON assignment.user_profile_id = user_profile.id
                AND assignment.organization_id = NEW.organization_id
               JOIN roles role
                 ON role.id = assignment.role_id
                AND role.status = 'ACTIVE'
               JOIN role_permissions mapping ON mapping.role_id = role.id
               JOIN permissions permission
                 ON permission.id = mapping.permission_id
               WHERE user_profile.id = NEW.delegator_user_id
                 AND user_profile.status = 'ACTIVE'
                 AND assignment.status = 'ACTIVE'
                 AND assignment.valid_from <= NEW.valid_from::date
                 AND (
                     assignment.valid_to IS NULL
                     OR assignment.valid_to >= NEW.valid_to::date
                 )
                 AND permission.code = requested.action_code
                 AND (
                     (assignment.scope_type = 'ORGANIZATION'
                      AND assignment.scope_id = NEW.organization_id)
                     OR (assignment.scope_type = 'ENGAGEMENT'
                         AND assignment.scope_id = NEW.engagement_id)
                     OR (assignment.scope_type = 'PROJECT'
                         AND assignment.scope_id = NEW.project_id)
                 )
           )
    ) THEN
        RAISE EXCEPTION
            'Delegation action exceeds the delegator effective authority'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER f01_delegation_scope_gate
BEFORE INSERT OR UPDATE OF organization_id, engagement_id, project_id,
    delegator_user_id, delegate_user_id ON delegations
FOR EACH ROW EXECUTE FUNCTION f01_delegation_scope_guard();

CREATE TABLE core_approval_requests (
    id UUID PRIMARY KEY,
    policy_version_id UUID NOT NULL REFERENCES approval_policy_versions(id),
    engagement_id UUID NOT NULL REFERENCES engagements(id),
    project_id UUID REFERENCES projects(id),
    object_type VARCHAR(80) NOT NULL,
    object_id UUID NOT NULL,
    object_version BIGINT NOT NULL CHECK (object_version >= 0),
    object_hash VARCHAR(64) NOT NULL CHECK (object_hash ~ '^[0-9a-f]{64}$'),
    required_permission_code VARCHAR(128) NOT NULL REFERENCES permissions(code),
    current_stage_order INTEGER NOT NULL DEFAULT 1
        CHECK (current_stage_order > 0),
    version BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING' CHECK (status IN (
        'PENDING', 'APPROVED', 'REJECTED', 'CHANGES_REQUESTED',
        'CANCELLED', 'EXPIRED', 'SUPERSEDED'
    )),
    requested_by_subject VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
    ,UNIQUE (requested_by_subject, idempotency_key)
);

CREATE UNIQUE INDEX uq_f01_active_approval_request
    ON core_approval_requests(
        engagement_id, object_type, object_id, object_version, object_hash)
    WHERE status = 'PENDING';

CREATE OR REPLACE FUNCTION f01_approval_request_scope_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM approval_policy_versions version
        JOIN approval_policies policy ON policy.id = version.policy_id
        WHERE version.id = NEW.policy_version_id
          AND version.status = 'PUBLISHED'
          AND policy.engagement_id = NEW.engagement_id
          AND (
              policy.project_id IS NULL
              OR policy.project_id = NEW.project_id
          )
    ) THEN
        RAISE EXCEPTION
            'Approval request policy must be published in the same scope'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.project_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM projects project
        WHERE project.id = NEW.project_id
          AND project.engagement_id = NEW.engagement_id
    ) THEN
        RAISE EXCEPTION
            'Approval request project must belong to the engagement'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER f01_approval_request_scope_gate
BEFORE INSERT OR UPDATE OF policy_version_id, engagement_id, project_id
ON core_approval_requests
FOR EACH ROW EXECUTE FUNCTION f01_approval_request_scope_guard();

CREATE TABLE core_approval_stage_snapshots (
    request_id UUID NOT NULL REFERENCES core_approval_requests(id),
    stage_order INTEGER NOT NULL CHECK (stage_order > 0),
    policy_version_id UUID NOT NULL REFERENCES approval_policy_versions(id),
    contact_group_id UUID REFERENCES contact_groups(id),
    contact_group_version BIGINT,
    quorum_mode VARCHAR(16) NOT NULL
        CHECK (quorum_mode IN ('ANY_ONE', 'ALL', 'N_OF_M')),
    quorum_required INTEGER NOT NULL CHECK (quorum_required > 0),
    allow_delegation BOOLEAN NOT NULL,
    eligible_user_ids UUID[] NOT NULL
        CHECK (cardinality(eligible_user_ids) > 0),
    snapshotted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (request_id, stage_order),
    CHECK (
        (contact_group_id IS NULL AND contact_group_version IS NULL)
        OR (contact_group_id IS NOT NULL
            AND contact_group_version IS NOT NULL)
    )
);

CREATE TABLE core_approval_actions (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES core_approval_requests(id),
    request_version BIGINT NOT NULL CHECK (request_version > 0),
    stage_order INTEGER NOT NULL CHECK (stage_order > 0),
    decision VARCHAR(24) NOT NULL CHECK (decision IN (
        'APPROVED', 'REJECTED', 'CHANGES_REQUESTED', 'CANCELLED'
    )),
    actor_user_id UUID NOT NULL REFERENCES user_profiles(id),
    actor_subject VARCHAR(255) NOT NULL,
    authority_snapshot JSONB NOT NULL
        CHECK (
            jsonb_typeof(authority_snapshot) = 'object'
            AND authority_snapshot <> '{}'::jsonb
        ),
    delegated_from_user_id UUID REFERENCES user_profiles(id),
    delegation_id UUID REFERENCES delegations(id),
    idempotency_key VARCHAR(160) NOT NULL,
    source VARCHAR(32) NOT NULL CHECK (source IN (
        'IN_APP', 'SECURE_EMAIL_LINK', 'VERIFIED_EMAIL_REPLY',
        'HISTORICAL_IMPORT', 'MANUAL_EVIDENCE'
    )),
    reason VARCHAR(1000),
    acted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (request_id, stage_order, actor_user_id),
    UNIQUE (request_id, request_version),
    UNIQUE (actor_subject, idempotency_key)
);

CREATE UNIQUE INDEX uq_f01_approval_action_authority
    ON core_approval_actions(
        request_id,
        stage_order,
        (COALESCE(delegated_from_user_id, actor_user_id)));

CREATE OR REPLACE FUNCTION f01_actor_eligible_for_stage(
    policy_version UUID,
    requested_stage INTEGER,
    candidate_user UUID,
    request_engagement UUID,
    request_project UUID
)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
SET search_path = pg_catalog, public
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM approval_policy_stages stage
        JOIN user_profiles user_profile
          ON user_profile.id = candidate_user
         AND user_profile.status = 'ACTIVE'
        WHERE stage.policy_version_id = policy_version
          AND stage.stage_order = requested_stage
          AND EXISTS (
              SELECT 1
              FROM memberships membership
              JOIN engagements engagement
                ON engagement.id = request_engagement
              WHERE membership.user_profile_id = candidate_user
                AND membership.status = 'ACTIVE'
                AND membership.valid_from <= CURRENT_DATE
                AND (
                    membership.valid_to IS NULL
                    OR membership.valid_to >= CURRENT_DATE
                )
                AND membership.organization_id IN (
                    engagement.client_organization_id,
                    engagement.vendor_organization_id,
                    engagement.procurement_organization_id)
          )
          AND (
              stage.explicit_assignee_id = candidate_user
              OR (
                  stage.contact_group_id IS NOT NULL
                  AND EXISTS (
                      SELECT 1
                      FROM contact_group_members member
                      WHERE member.contact_group_id =
                          stage.contact_group_id
                        AND member.user_profile_id = candidate_user
                        AND member.status = 'ACTIVE'
                        AND member.verified
                        AND member.valid_from <= CURRENT_DATE
                        AND (
                            member.valid_to IS NULL
                            OR member.valid_to >= CURRENT_DATE)
                  )
              )
              OR (
                  stage.role_code IS NOT NULL
                  AND EXISTS (
                      SELECT 1
                      FROM role_assignments assignment
                      JOIN roles role
                        ON role.id = assignment.role_id
                       AND role.status = 'ACTIVE'
                      JOIN memberships membership
                        ON membership.user_profile_id =
                            assignment.user_profile_id
                       AND membership.organization_id =
                            assignment.organization_id
                      JOIN engagements engagement
                        ON engagement.id = request_engagement
                      WHERE assignment.user_profile_id = candidate_user
                        AND role.code = stage.role_code
                        AND assignment.status = 'ACTIVE'
                        AND assignment.valid_from <= CURRENT_DATE
                        AND (
                            assignment.valid_to IS NULL
                            OR assignment.valid_to >= CURRENT_DATE)
                        AND membership.status = 'ACTIVE'
                        AND membership.valid_from <= CURRENT_DATE
                        AND (
                            membership.valid_to IS NULL
                            OR membership.valid_to >= CURRENT_DATE)
                        AND assignment.organization_id IN (
                            engagement.client_organization_id,
                            engagement.vendor_organization_id,
                            engagement.procurement_organization_id)
                        AND (
                            (
                                assignment.scope_type = 'ORGANIZATION'
                                AND assignment.scope_id =
                                    assignment.organization_id
                            )
                            OR (
                                assignment.scope_type = 'ENGAGEMENT'
                                AND assignment.scope_id =
                                    request_engagement
                            )
                            OR (
                                assignment.scope_type = 'PROJECT'
                                AND request_project IS NOT NULL
                                AND assignment.scope_id = request_project
                            )
                        )
                  )
              )
          )
    )
$$;

CREATE OR REPLACE FUNCTION f01_approval_action_scope_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
DECLARE
    request_policy_version UUID;
    request_engagement UUID;
    request_project UUID;
    request_stage INTEGER;
    current_request_version BIGINT;
    request_permission TEXT;
    requester_subject TEXT;
    self_approval_prohibited BOOLEAN;
    evidence_required_value BOOLEAN;
    actor_subject_value TEXT;
    authority_subject_value TEXT;
BEGIN
    SELECT request.policy_version_id, request.engagement_id,
           request.project_id, request.current_stage_order,
           request.version, request.required_permission_code,
           request.requested_by_subject,
           policy_version.prohibit_self_approval,
           policy_version.evidence_required
      INTO request_policy_version, request_engagement, request_project,
           request_stage, current_request_version, request_permission,
           requester_subject, self_approval_prohibited,
           evidence_required_value
    FROM core_approval_requests request
    JOIN approval_policy_versions policy_version
      ON policy_version.id = request.policy_version_id
    WHERE request.id = NEW.request_id AND request.status = 'PENDING';
    IF request_policy_version IS NULL THEN
        RAISE EXCEPTION 'Approval request is not pending'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.stage_order <> request_stage THEN
        RAISE EXCEPTION
            'Approval action must target the current request stage'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.request_version <> current_request_version + 1 THEN
        RAISE EXCEPTION
            'Approval action request version must be the next version'
            USING ERRCODE = '23514';
    END IF;
    IF evidence_required_value
       AND btrim(COALESCE(NEW.reason, '')) = '' THEN
        RAISE EXCEPTION 'Approval action evidence is required'
            USING ERRCODE = '23514';
    END IF;
    SELECT identity_subject INTO actor_subject_value
    FROM user_profiles
    WHERE id = NEW.actor_user_id AND status = 'ACTIVE';
    IF actor_subject_value IS DISTINCT FROM NEW.actor_subject THEN
        RAISE EXCEPTION 'Approval actor identity does not match subject'
            USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM memberships membership
        JOIN engagements engagement
          ON engagement.id = request_engagement
        WHERE membership.user_profile_id = NEW.actor_user_id
          AND membership.status = 'ACTIVE'
          AND membership.valid_from <= CURRENT_DATE
          AND (
              membership.valid_to IS NULL
              OR membership.valid_to >= CURRENT_DATE)
          AND membership.organization_id IN (
              engagement.client_organization_id,
              engagement.vendor_organization_id,
              engagement.procurement_organization_id)
    ) THEN
        RAISE EXCEPTION 'Approval actor is not an active participant'
            USING ERRCODE = '23514';
    END IF;
    IF (NEW.delegation_id IS NULL)
       <> (NEW.delegated_from_user_id IS NULL) THEN
        RAISE EXCEPTION
            'Delegation evidence must include both delegation and authority holder'
            USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM core_approval_stage_snapshots snapshot
        WHERE snapshot.request_id = NEW.request_id
          AND snapshot.stage_order = NEW.stage_order
          AND NEW.actor_user_id = ANY(snapshot.eligible_user_ids)
    ) AND NOT EXISTS (
        SELECT 1
        FROM core_approval_stage_snapshots snapshot
        JOIN delegations delegation
          ON delegation.id = NEW.delegation_id
        WHERE snapshot.request_id = NEW.request_id
          AND snapshot.stage_order = NEW.stage_order
          AND snapshot.allow_delegation
          AND delegation.engagement_id = request_engagement
          AND delegation.delegator_user_id = NEW.delegated_from_user_id
          AND delegation.delegate_user_id = NEW.actor_user_id
          AND delegation.status = 'ACTIVE'
          AND CURRENT_TIMESTAMP >= delegation.valid_from
          AND CURRENT_TIMESTAMP < delegation.valid_to
          AND request_permission = ANY(delegation.action_codes)
          AND NEW.delegated_from_user_id =
              ANY(snapshot.eligible_user_ids)
          AND EXISTS (
              SELECT 1
              FROM memberships membership
              JOIN engagements engagement
                ON engagement.id = request_engagement
              WHERE membership.user_profile_id = NEW.actor_user_id
                AND membership.organization_id =
                    delegation.organization_id
                AND membership.status = 'ACTIVE'
                AND membership.valid_from <= CURRENT_DATE
                AND (
                    membership.valid_to IS NULL
                    OR membership.valid_to >= CURRENT_DATE)
                AND membership.organization_id IN (
                    engagement.client_organization_id,
                    engagement.vendor_organization_id,
                    engagement.procurement_organization_id)
          )
          AND (
              delegation.project_id IS NULL
              OR EXISTS (
                  SELECT 1
                  FROM core_approval_requests request
                  WHERE request.id = NEW.request_id
                    AND request.project_id = delegation.project_id
              )
          )
    ) THEN
        RAISE EXCEPTION
            'Approval actor is not eligible for this policy stage'
            USING ERRCODE = '23514';
    END IF;
    SELECT identity_subject INTO authority_subject_value
    FROM user_profiles
    WHERE id = COALESCE(
        NEW.delegated_from_user_id, NEW.actor_user_id);
    IF self_approval_prohibited
       AND authority_subject_value = requester_subject THEN
        RAISE EXCEPTION
            'The request creator cannot act through direct or delegated authority'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER f01_approval_action_scope_gate
BEFORE INSERT ON core_approval_actions
FOR EACH ROW EXECUTE FUNCTION f01_approval_action_scope_guard();

CREATE OR REPLACE FUNCTION f01_approval_request_mutation_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
DECLARE
    action_decision TEXT;
    required_quorum INTEGER;
    approval_count INTEGER;
    next_stage INTEGER;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Approval requests cannot be deleted'
            USING ERRCODE = '55000';
    END IF;
    IF ROW(
        NEW.id, NEW.policy_version_id, NEW.engagement_id, NEW.project_id,
        NEW.object_type, NEW.object_id, NEW.object_version, NEW.object_hash,
        NEW.required_permission_code, NEW.requested_by_subject,
        NEW.idempotency_key, NEW.requested_at
    ) IS DISTINCT FROM ROW(
        OLD.id, OLD.policy_version_id, OLD.engagement_id, OLD.project_id,
        OLD.object_type, OLD.object_id, OLD.object_version, OLD.object_hash,
        OLD.required_permission_code, OLD.requested_by_subject,
        OLD.idempotency_key, OLD.requested_at
    ) THEN
        RAISE EXCEPTION 'Approval request identity and evidence are immutable'
            USING ERRCODE = '55000';
    END IF;
    IF OLD.status <> 'PENDING'
       OR NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'Approval request mutation requires one pending action'
            USING ERRCODE = '23514';
    END IF;
    SELECT action.decision INTO action_decision
    FROM core_approval_actions action
    WHERE action.request_id = OLD.id
      AND action.request_version = NEW.version
      AND action.stage_order = OLD.current_stage_order;
    IF action_decision IS NULL THEN
        RAISE EXCEPTION 'Approval request mutation lacks bound action evidence'
            USING ERRCODE = '23514';
    END IF;
    IF action_decision <> 'APPROVED' THEN
        IF NEW.status <> action_decision
           OR NEW.current_stage_order <> OLD.current_stage_order THEN
            RAISE EXCEPTION
                'Approval request terminal state must match its action'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    SELECT snapshot.quorum_required,
           count(DISTINCT COALESCE(
               action.delegated_from_user_id, action.actor_user_id))
               FILTER (WHERE action.decision = 'APPROVED'),
           (
               SELECT min(later.stage_order)
               FROM core_approval_stage_snapshots later
               WHERE later.request_id = OLD.id
                 AND later.stage_order > OLD.current_stage_order
           )
      INTO required_quorum, approval_count, next_stage
    FROM core_approval_stage_snapshots snapshot
    LEFT JOIN core_approval_actions action
      ON action.request_id = OLD.id
     AND action.stage_order = OLD.current_stage_order
    WHERE snapshot.request_id = OLD.id
      AND snapshot.stage_order = OLD.current_stage_order
    GROUP BY snapshot.request_id, snapshot.stage_order,
             snapshot.quorum_required;
    IF required_quorum IS NULL THEN
        RAISE EXCEPTION 'Approval request stage is unavailable'
            USING ERRCODE = '23514';
    END IF;
    IF approval_count < required_quorum THEN
        IF NEW.status <> 'PENDING'
           OR NEW.current_stage_order <> OLD.current_stage_order THEN
            RAISE EXCEPTION 'Approval quorum has not been met'
                USING ERRCODE = '23514';
        END IF;
    ELSIF next_stage IS NULL THEN
        IF NEW.status <> 'APPROVED'
           OR NEW.current_stage_order <> OLD.current_stage_order THEN
            RAISE EXCEPTION 'Final approval must complete the request'
                USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.status <> 'PENDING'
       OR NEW.current_stage_order <> next_stage THEN
        RAISE EXCEPTION 'Approval must advance to the next ordered stage'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER f01_approval_request_mutation_gate
BEFORE UPDATE OR DELETE ON core_approval_requests
FOR EACH ROW EXECUTE FUNCTION f01_approval_request_mutation_guard();

CREATE TABLE f04_core_reopen_approval_bindings (
    reopen_request_id UUID PRIMARY KEY
        REFERENCES month_reopen_requests(id),
    core_approval_request_id UUID NOT NULL UNIQUE
        REFERENCES core_approval_requests(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE OR REPLACE FUNCTION f01_f04_core_reopen_binding_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM month_reopen_requests reopen_request
        JOIN engagement_months month
          ON month.id = reopen_request.engagement_month_id
        JOIN core_approval_requests approval
          ON approval.id = NEW.core_approval_request_id
         AND approval.engagement_id = month.engagement_id
         AND approval.object_type = 'ENGAGEMENT_MONTH'
         AND approval.object_id = month.id
         AND approval.object_version = month.governance_version
         AND approval.requested_by_subject =
             reopen_request.requested_by_subject
         AND approval.status = 'PENDING'
        WHERE reopen_request.id = NEW.reopen_request_id
          AND reopen_request.status = 'REQUESTED'
          AND month.state = 'REOPEN_REQUESTED'
    ) THEN
        RAISE EXCEPTION
            'F04 reopen binding requires an exact pending core approval request'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER f01_f04_core_reopen_binding_scope_gate
BEFORE INSERT ON f04_core_reopen_approval_bindings
FOR EACH ROW EXECUTE FUNCTION f01_f04_core_reopen_binding_guard();

CREATE TRIGGER f01_f04_core_reopen_bindings_immutable
BEFORE UPDATE OR DELETE ON f04_core_reopen_approval_bindings
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

ALTER TABLE engagement_months
    ADD COLUMN governance_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN governance_configuration_version_id UUID
        REFERENCES engagement_configuration_versions(id);

CREATE TABLE engagement_month_transition_history (
    id UUID PRIMARY KEY,
    engagement_month_id UUID NOT NULL REFERENCES engagement_months(id),
    from_state VARCHAR(40) NOT NULL,
    to_state VARCHAR(40) NOT NULL,
    from_version BIGINT NOT NULL,
    to_version BIGINT NOT NULL,
    actor_subject VARCHAR(255) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    correlation_id UUID,
    transitioned_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE core_audit_events (
    id UUID PRIMARY KEY,
    engagement_id UUID REFERENCES engagements(id),
    organization_id UUID REFERENCES organizations(id),
    event_type VARCHAR(100) NOT NULL,
    actor_subject VARCHAR(255) NOT NULL,
    subject_type VARCHAR(80) NOT NULL,
    subject_id UUID NOT NULL,
    subject_version BIGINT,
    correlation_id UUID,
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE OR REPLACE FUNCTION f01_record_month_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
DECLARE
    actor_value TEXT;
    reason_value TEXT;
    correlation_value TEXT;
BEGIN
    IF NEW.state IS DISTINCT FROM OLD.state THEN
        IF NOT (
            (OLD.state = 'HISTORICAL_DRAFT'
                AND NEW.state IN (
                    'HISTORICAL_IMPORT_IN_PROGRESS', 'HISTORICAL_REVIEW'))
            OR (OLD.state = 'HISTORICAL_IMPORT_IN_PROGRESS'
                AND NEW.state = 'HISTORICAL_REVIEW')
            OR (OLD.state = 'HISTORICAL_REVIEW'
                AND NEW.state IN (
                    'HISTORICAL_PENDING_CERTIFICATION',
                    'HISTORICAL_PENDING_CONFIRMATION'))
            OR (OLD.state = 'HISTORICAL_PENDING_CERTIFICATION'
                AND NEW.state = 'HISTORICAL_PENDING_CONFIRMATION')
            OR (OLD.state = 'HISTORICAL_PENDING_CONFIRMATION'
                AND NEW.state = 'CONFIRMED')
            OR (OLD.state = 'DRAFT' AND NEW.state = 'PLANNING')
            OR (OLD.state = 'PLANNING'
                AND NEW.state IN (
                    'PLAN_PENDING_APPROVAL', 'PLAN_APPROVED'))
            OR (OLD.state = 'PLAN_PENDING_APPROVAL'
                AND NEW.state = 'PLAN_APPROVED')
            OR (OLD.state = 'PLAN_APPROVED'
                AND NEW.state IN ('ACTIVE', 'REOPEN_REQUESTED'))
            OR (OLD.state = 'ACTIVE'
                AND NEW.state IN (
                    'DELIVERY_SUBMITTED', 'REOPEN_REQUESTED'))
            OR (OLD.state = 'DELIVERY_SUBMITTED'
                AND NEW.state IN ('DELIVERY_REVIEW', 'REOPEN_REQUESTED'))
            OR (OLD.state = 'DELIVERY_REVIEW'
                AND NEW.state IN (
                    'CONFIRMATION_PENDING', 'REOPEN_REQUESTED'))
            OR (OLD.state = 'CONFIRMATION_PENDING'
                AND NEW.state IN (
                    'CONFIRMED', 'DELIVERY_REVIEW', 'REOPEN_REQUESTED'))
            OR (OLD.state = 'CONFIRMED'
                AND NEW.state IN (
                    'INVOICE_READY', 'CLOSED', 'REOPEN_REQUESTED'))
            OR (OLD.state = 'INVOICE_READY'
                AND NEW.state IN (
                    'INVOICE_SUBMITTED', 'REOPEN_REQUESTED'))
            OR (OLD.state = 'INVOICE_SUBMITTED'
                AND NEW.state IN ('CLOSED', 'REOPEN_REQUESTED'))
            OR (OLD.state = 'CLOSED' AND NEW.state = 'REOPEN_REQUESTED')
            OR (OLD.state = 'REOPEN_REQUESTED'
                AND NEW.state IN ('REOPENED', 'CONFIRMED', 'CLOSED'))
            OR (OLD.state = 'REOPENED'
                AND NEW.state IN (
                    'PLANNING', 'ACTIVE', 'DELIVERY_REVIEW',
                    'CONFIRMATION_PENDING', 'CONFIRMED'))
        ) THEN
            RAISE EXCEPTION 'Invalid engagement month transition: % -> %',
                OLD.state, NEW.state
                USING ERRCODE = '23514';
        END IF;
        IF OLD.state = 'REOPEN_REQUESTED'
           AND NEW.state = 'REOPENED'
           AND NOT EXISTS (
               SELECT 1
               FROM core_approval_requests request
               WHERE request.engagement_id = OLD.engagement_id
                 AND request.object_type = 'ENGAGEMENT_MONTH'
                 AND request.object_id = OLD.id
                 AND request.object_version = OLD.governance_version
                 AND request.status = 'APPROVED'
           ) THEN
            RAISE EXCEPTION
                'Reopen transition requires completed bound approval evidence'
                USING ERRCODE = '23514';
        END IF;
        IF NEW.state = 'PLANNING' AND NOT EXISTS (
            SELECT 1
            FROM engagement_configuration_versions configuration
            WHERE configuration.id =
                NEW.governance_configuration_version_id
              AND configuration.engagement_id = NEW.engagement_id
              AND configuration.status = 'PUBLISHED'
              AND configuration.valid_from <= NEW.month_start_date
              AND (
                  configuration.valid_to IS NULL
                  OR configuration.valid_to >= NEW.month_start_date
              )
        ) THEN
            RAISE EXCEPTION
                'Planning requires the effective engagement configuration snapshot'
                USING ERRCODE = '23514';
        END IF;
        actor_value := COALESCE(
            NULLIF(current_setting('vms.actor_subject', TRUE), ''),
            'SYSTEM:APPLICATION');
        reason_value := COALESCE(
            NULLIF(current_setting('vms.transition_reason', TRUE), ''),
            'Internal workflow transition');
        correlation_value := NULLIF(
            current_setting('vms.correlation_id', TRUE), '');
        IF NEW.governance_version = OLD.governance_version THEN
            NEW.governance_version := OLD.governance_version + 1;
        END IF;
        INSERT INTO engagement_month_transition_history(
            id, engagement_month_id, from_state, to_state,
            from_version, to_version, actor_subject, reason, correlation_id)
        VALUES (
            gen_random_uuid(), OLD.id, OLD.state, NEW.state,
            OLD.governance_version, NEW.governance_version,
            actor_value, reason_value,
            CASE WHEN correlation_value IS NULL THEN NULL
                 ELSE correlation_value::uuid END);
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER f01_engagement_month_transition_history_gate
BEFORE UPDATE OF state ON engagement_months
FOR EACH ROW EXECUTE FUNCTION f01_record_month_transition();

CREATE OR REPLACE FUNCTION f01_reject_immutable_change()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    RAISE EXCEPTION 'Immutable F01 evidence cannot be changed'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER f01_month_transition_history_immutable
BEFORE UPDATE OR DELETE ON engagement_month_transition_history
FOR EACH ROW EXECUTE FUNCTION f01_reject_immutable_change();
CREATE TRIGGER f01_core_approval_actions_immutable
BEFORE UPDATE OR DELETE ON core_approval_actions
FOR EACH ROW EXECUTE FUNCTION f01_reject_immutable_change();
CREATE TRIGGER f01_core_approval_stage_snapshots_immutable
BEFORE UPDATE OR DELETE ON core_approval_stage_snapshots
FOR EACH ROW EXECUTE FUNCTION f01_reject_immutable_change();
CREATE TRIGGER f01_core_audit_events_immutable
BEFORE UPDATE OR DELETE ON core_audit_events
FOR EACH ROW EXECUTE FUNCTION f01_reject_immutable_change();

CREATE INDEX idx_f01_contact_groups_engagement
    ON contact_groups(engagement_id, status);
CREATE INDEX idx_f01_contact_members_group_effective
    ON contact_group_members(contact_group_id, status, valid_from, valid_to);
CREATE INDEX idx_f01_approval_policy_engagement
    ON approval_policies(engagement_id, status);
CREATE INDEX idx_f01_delegation_effective
    ON delegations(organization_id, engagement_id, status, valid_from, valid_to);
CREATE INDEX idx_f01_month_transition_history
    ON engagement_month_transition_history(
        engagement_month_id, transitioned_at DESC);

-- V21 secure defaults intentionally require explicit post-migration grants.
GRANT SELECT, INSERT, UPDATE, DELETE ON
    contact_groups,
    contact_group_members,
    approval_policies,
    approval_policy_versions,
    approval_policy_stages,
    delegations,
    core_approval_requests
TO vms_app_runtime;

GRANT SELECT, INSERT ON engagement_configuration_versions
TO vms_app_runtime;

GRANT SELECT, INSERT ON
    core_approval_stage_snapshots,
    core_approval_actions,
    f04_core_reopen_approval_bindings
TO vms_app_runtime;

GRANT SELECT, INSERT ON
    engagement_month_transition_history,
    core_audit_events
TO vms_app_runtime;

GRANT EXECUTE ON FUNCTION
    f01_engagement_default_project_scope_guard(),
    f01_configuration_scope_guard(),
    f01_configuration_version_guard(),
    f01_contact_member_scope_guard(),
    f01_project_engagement_scope_guard(),
    f01_approval_policy_version_guard(),
    f01_approval_policy_current_scope_guard(),
    f01_approval_stage_scope_guard(),
    f01_delegation_scope_guard(),
    f01_approval_request_scope_guard(),
    f01_actor_eligible_for_stage(UUID, INTEGER, UUID, UUID, UUID),
    f01_approval_action_scope_guard(),
    f01_approval_request_mutation_guard(),
    f01_f04_core_reopen_binding_guard(),
    f01_record_month_transition(),
    f01_reject_immutable_change()
TO vms_app_runtime;

REVOKE UPDATE, DELETE, TRUNCATE ON
    engagement_configuration_versions,
    approval_policy_versions,
    core_approval_stage_snapshots,
    core_approval_actions,
    f04_core_reopen_approval_bindings,
    engagement_month_transition_history,
    core_audit_events
FROM vms_app_runtime;

REVOKE DELETE, TRUNCATE ON core_approval_requests
FROM vms_app_runtime;

REVOKE ALL ON FUNCTION
    f01_engagement_default_project_scope_guard(),
    f01_configuration_scope_guard(),
    f01_configuration_version_guard(),
    f01_contact_member_scope_guard(),
    f01_project_engagement_scope_guard(),
    f01_approval_policy_version_guard(),
    f01_approval_policy_current_scope_guard(),
    f01_approval_stage_scope_guard(),
    f01_delegation_scope_guard(),
    f01_approval_request_scope_guard(),
    f01_actor_eligible_for_stage(UUID, INTEGER, UUID, UUID, UUID),
    f01_approval_action_scope_guard(),
    f01_approval_request_mutation_guard(),
    f01_f04_core_reopen_binding_guard(),
    f01_record_month_transition(),
    f01_reject_immutable_change()
FROM PUBLIC;

ALTER TABLE engagement_configuration_versions OWNER TO vms_migration_owner;
ALTER TABLE contact_groups OWNER TO vms_migration_owner;
ALTER TABLE contact_group_members OWNER TO vms_migration_owner;
ALTER TABLE approval_policies OWNER TO vms_migration_owner;
ALTER TABLE approval_policy_versions OWNER TO vms_migration_owner;
ALTER TABLE approval_policy_stages OWNER TO vms_migration_owner;
ALTER TABLE delegations OWNER TO vms_migration_owner;
ALTER TABLE core_approval_requests OWNER TO vms_migration_owner;
ALTER TABLE core_approval_stage_snapshots OWNER TO vms_migration_owner;
ALTER TABLE core_approval_actions OWNER TO vms_migration_owner;
ALTER TABLE f04_core_reopen_approval_bindings OWNER TO vms_migration_owner;
ALTER TABLE engagement_month_transition_history OWNER TO vms_migration_owner;
ALTER TABLE core_audit_events OWNER TO vms_migration_owner;
ALTER FUNCTION f01_engagement_default_project_scope_guard()
    OWNER TO vms_migration_owner;
ALTER FUNCTION f01_configuration_scope_guard()
    OWNER TO vms_migration_owner;
ALTER FUNCTION f01_configuration_version_guard()
    OWNER TO vms_migration_owner;
ALTER FUNCTION f01_contact_member_scope_guard()
    OWNER TO vms_migration_owner;
ALTER FUNCTION f01_project_engagement_scope_guard()
    OWNER TO vms_migration_owner;
ALTER FUNCTION f01_approval_policy_version_guard()
    OWNER TO vms_migration_owner;
ALTER FUNCTION f01_approval_policy_current_scope_guard()
    OWNER TO vms_migration_owner;
ALTER FUNCTION f01_approval_stage_scope_guard()
    OWNER TO vms_migration_owner;
ALTER FUNCTION f01_delegation_scope_guard()
    OWNER TO vms_migration_owner;
ALTER FUNCTION f01_approval_request_scope_guard()
    OWNER TO vms_migration_owner;
ALTER FUNCTION f01_actor_eligible_for_stage(
    UUID, INTEGER, UUID, UUID, UUID)
    OWNER TO vms_migration_owner;
ALTER FUNCTION f01_approval_action_scope_guard()
    OWNER TO vms_migration_owner;
ALTER FUNCTION f01_approval_request_mutation_guard()
    OWNER TO vms_migration_owner;
ALTER FUNCTION f01_f04_core_reopen_binding_guard()
    OWNER TO vms_migration_owner;
ALTER FUNCTION f01_record_month_transition() OWNER TO vms_migration_owner;
ALTER FUNCTION f01_reject_immutable_change() OWNER TO vms_migration_owner;
