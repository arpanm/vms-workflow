CREATE TABLE organizations (
    id UUID PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    legal_name VARCHAR(255) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    organization_type VARCHAR(32) NOT NULL
        CHECK (organization_type IN ('CLIENT', 'VENDOR', 'PROCUREMENT', 'PLATFORM_OPERATOR', 'OTHER')),
    external_identifier VARCHAR(128),
    primary_domain VARCHAR(255),
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE', 'ARCHIVED')),
    default_timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Kolkata',
    default_locale VARCHAR(16) NOT NULL DEFAULT 'en-IN',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE user_profiles (
    id UUID PRIMARY KEY,
    identity_subject VARCHAR(255) NOT NULL UNIQUE,
    email VARCHAR(320) NOT NULL UNIQUE,
    display_name VARCHAR(255) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED', 'LOCKED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE memberships (
    id UUID PRIMARY KEY,
    user_profile_id UUID NOT NULL REFERENCES user_profiles(id),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    role_code VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE', 'REVOKED')),
    valid_from DATE NOT NULL DEFAULT CURRENT_DATE,
    valid_to DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_membership_scope UNIQUE (user_profile_id, organization_id, role_code),
    CONSTRAINT ck_membership_dates CHECK (valid_to IS NULL OR valid_to >= valid_from)
);
CREATE INDEX idx_memberships_subject_scope ON memberships(user_profile_id, organization_id, status);

CREATE TABLE engagements (
    id UUID PRIMARY KEY,
    engagement_code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    client_organization_id UUID NOT NULL REFERENCES organizations(id),
    vendor_organization_id UUID NOT NULL REFERENCES organizations(id),
    procurement_organization_id UUID REFERENCES organizations(id),
    engagement_model VARCHAR(48) NOT NULL
        CHECK (engagement_model IN ('DEDICATED_RESOURCE_MONTHLY', 'FIXED_COST_DELIVERY', 'STAFF_AUGMENTATION', 'HYBRID')),
    start_date DATE NOT NULL,
    end_date DATE,
    status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'SUSPENDED', 'COMPLETED', 'ARCHIVED')),
    billing_cycle VARCHAR(32) NOT NULL DEFAULT 'CALENDAR_MONTH' CHECK (billing_cycle = 'CALENDAR_MONTH'),
    attendance_required BOOLEAN NOT NULL DEFAULT TRUE,
    deliverable_baseline_required BOOLEAN NOT NULL DEFAULT TRUE,
    delivery_certification_required BOOLEAN NOT NULL DEFAULT TRUE,
    business_confirmation_required BOOLEAN NOT NULL DEFAULT TRUE,
    commercial_data_policy VARCHAR(64) NOT NULL DEFAULT 'NO_RATE_OR_SALARY_STORAGE'
        CHECK (commercial_data_policy = 'NO_RATE_OR_SALARY_STORAGE'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_engagement_distinct_parties CHECK (client_organization_id <> vendor_organization_id),
    CONSTRAINT ck_engagement_dates CHECK (end_date IS NULL OR end_date >= start_date)
);
CREATE INDEX idx_engagement_client ON engagements(client_organization_id);
CREATE INDEX idx_engagement_vendor ON engagements(vendor_organization_id);
CREATE INDEX idx_engagement_procurement ON engagements(procurement_organization_id);

CREATE TABLE projects (
    id UUID PRIMARY KEY,
    engagement_id UUID NOT NULL REFERENCES engagements(id),
    project_code VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    parent_project_id UUID REFERENCES projects(id),
    start_date DATE NOT NULL,
    end_date DATE,
    status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'INACTIVE', 'ARCHIVED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_project_engagement_code UNIQUE (engagement_id, project_code),
    CONSTRAINT ck_project_dates CHECK (end_date IS NULL OR end_date >= start_date),
    CONSTRAINT ck_project_not_own_parent CHECK (parent_project_id IS NULL OR parent_project_id <> id)
);
CREATE INDEX idx_projects_engagement ON projects(engagement_id);

CREATE TABLE engagement_months (
    id UUID PRIMARY KEY,
    engagement_id UUID NOT NULL REFERENCES engagements(id),
    month_start_date DATE NOT NULL,
    state VARCHAR(40) NOT NULL CHECK (state IN (
        'HISTORICAL_DRAFT', 'HISTORICAL_REVIEW', 'HISTORICAL_PENDING_CONFIRMATION',
        'DRAFT', 'PLANNING', 'PLAN_PENDING_APPROVAL', 'PLAN_APPROVED', 'ACTIVE',
        'DELIVERY_SUBMITTED', 'DELIVERY_REVIEW', 'CONFIRMATION_PENDING', 'CONFIRMED',
        'INVOICE_READY', 'INVOICE_SUBMITTED', 'CLOSED', 'REOPEN_REQUESTED', 'REOPENED'
    )),
    risk_status VARCHAR(24) NOT NULL DEFAULT 'ON_TRACK'
        CHECK (risk_status IN ('ON_TRACK', 'AT_RISK', 'BREACHED', 'BLOCKED', 'EXCEPTION_ACCEPTED')),
    historical_flag BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_engagement_month UNIQUE (engagement_id, month_start_date),
    CONSTRAINT ck_month_first_day CHECK (EXTRACT(DAY FROM month_start_date) = 1)
);
CREATE INDEX idx_engagement_months_engagement ON engagement_months(engagement_id);

-- These are imported baseline snapshots. The API never exposes mutation methods for them.
CREATE TABLE legacy_engagements (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    legacy_key VARCHAR(128) NOT NULL,
    payload JSONB NOT NULL,
    imported_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (organization_id, legacy_key)
);
CREATE TABLE legacy_requirements (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    legacy_key VARCHAR(128) NOT NULL,
    payload JSONB NOT NULL,
    imported_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (organization_id, legacy_key)
);
CREATE TABLE legacy_approvals (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    legacy_key VARCHAR(128) NOT NULL,
    payload JSONB NOT NULL,
    imported_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (organization_id, legacy_key)
);
CREATE TABLE legacy_uat_items (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    legacy_key VARCHAR(128) NOT NULL,
    payload JSONB NOT NULL,
    imported_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (organization_id, legacy_key)
);
CREATE TABLE legacy_invoices (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    legacy_key VARCHAR(128) NOT NULL,
    payload JSONB NOT NULL,
    imported_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (organization_id, legacy_key)
);

INSERT INTO organizations (id, code, legal_name, display_name, organization_type, primary_domain, status)
VALUES
    ('00000000-0000-0000-0000-000000000101', 'ARROWFOUNDRY', 'ArrowFoundry Private Limited', 'ArrowFoundry', 'VENDOR', 'arrowfoundry.com', 'ACTIVE'),
    ('00000000-0000-0000-0000-000000000102', 'RELIANCE_INTELLIGENCE', 'Reliance Intelligence Limited', 'Reliance Intelligence', 'CLIENT', 'ril.com', 'ACTIVE'),
    ('00000000-0000-0000-0000-000000000103', 'RELIANCE_PROCUREMENT', 'Reliance Central Procurement', 'Reliance Central Procurement', 'PROCUREMENT', 'ril.com', 'ACTIVE');

INSERT INTO engagements (id, engagement_code, name, client_organization_id, vendor_organization_id,
                         procurement_organization_id, engagement_model, start_date, status)
VALUES
    ('00000000-0000-0000-0000-000000000401', 'RI-AF-2026', 'Reliance Intelligence / ArrowFoundry Delivery',
     '00000000-0000-0000-0000-000000000102', '00000000-0000-0000-0000-000000000101',
     '00000000-0000-0000-0000-000000000103', 'DEDICATED_RESOURCE_MONTHLY', '2026-06-01', 'ACTIVE');

INSERT INTO projects (id, engagement_id, project_code, name, description, start_date, status)
VALUES
    ('00000000-0000-0000-0000-000000000501', '00000000-0000-0000-0000-000000000401', 'NAM', 'NAM', 'Initial delivery project', '2026-06-01', 'ACTIVE'),
    ('00000000-0000-0000-0000-000000000502', '00000000-0000-0000-0000-000000000401', 'AGENTIC_SHOPOS', 'Agentic ShopOS', 'Initial agentic commerce project', '2026-06-01', 'ACTIVE');

INSERT INTO engagement_months (id, engagement_id, month_start_date, state, risk_status)
VALUES
    ('00000000-0000-0000-0000-000000000601', '00000000-0000-0000-0000-000000000401', '2026-06-01', 'CONFIRMED', 'ON_TRACK');
