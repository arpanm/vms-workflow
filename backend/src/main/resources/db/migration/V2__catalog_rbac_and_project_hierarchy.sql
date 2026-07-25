CREATE TABLE roles (
    id UUID PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    description TEXT,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE TABLE permissions (
    id UUID PRIMARY KEY,
    code VARCHAR(128) NOT NULL UNIQUE,
    description TEXT NOT NULL
);

CREATE TABLE role_permissions (
    role_id UUID NOT NULL REFERENCES roles(id),
    permission_id UUID NOT NULL REFERENCES permissions(id),
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE role_assignments (
    id UUID PRIMARY KEY,
    user_profile_id UUID NOT NULL REFERENCES user_profiles(id),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    role_id UUID NOT NULL REFERENCES roles(id),
    scope_type VARCHAR(24) NOT NULL
        CHECK (scope_type IN ('ORGANIZATION', 'ENGAGEMENT', 'PROJECT')),
    scope_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'REVOKED')),
    valid_from DATE NOT NULL,
    valid_to DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_role_assignment_dates CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CONSTRAINT ck_organization_assignment_scope
        CHECK (scope_type <> 'ORGANIZATION' OR scope_id = organization_id),
    CONSTRAINT uq_role_assignment UNIQUE
        (user_profile_id, organization_id, role_id, scope_type, scope_id, valid_from)
);
CREATE INDEX idx_role_assignments_authority
    ON role_assignments(user_profile_id, organization_id, scope_type, scope_id, status);

ALTER TABLE projects
    ADD CONSTRAINT uq_project_id_engagement UNIQUE (id, engagement_id);
ALTER TABLE projects
    DROP CONSTRAINT projects_parent_project_id_fkey;
ALTER TABLE projects
    ADD CONSTRAINT fk_project_parent_same_engagement
        FOREIGN KEY (parent_project_id, engagement_id)
        REFERENCES projects(id, engagement_id);

INSERT INTO permissions (id, code, description) VALUES
    ('10000000-0000-0000-0000-000000000001', 'catalog.read', 'Read the authorized identity and catalog scope');

INSERT INTO roles (id, code, name, description) VALUES
    ('11000000-0000-0000-0000-000000000001', 'ORG_ADMIN', 'Organization administrator', 'Initial organization administration role template'),
    ('11000000-0000-0000-0000-000000000002', 'ENGAGEMENT_ADMIN', 'Engagement administrator', 'Initial engagement administration role template'),
    ('11000000-0000-0000-0000-000000000003', 'VENDOR_MANAGER', 'Vendor manager', 'Initial vendor delivery management role template'),
    ('11000000-0000-0000-0000-000000000004', 'CLIENT_PRODUCT_OWNER', 'Client product owner', 'Initial client product ownership role template'),
    ('11000000-0000-0000-0000-000000000005', 'AUDITOR_READONLY', 'Read-only auditor', 'Initial audit role template'),
    ('11000000-0000-0000-0000-000000000006', 'NO_ACCESS', 'No catalog access', 'Testable deny-by-default role template');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code IN ('ORG_ADMIN', 'ENGAGEMENT_ADMIN', 'VENDOR_MANAGER', 'CLIENT_PRODUCT_OWNER', 'AUDITOR_READONLY')
  AND p.code = 'catalog.read';

ALTER TABLE memberships
    ADD CONSTRAINT fk_membership_role_template
        FOREIGN KEY (role_code) REFERENCES roles(code);
