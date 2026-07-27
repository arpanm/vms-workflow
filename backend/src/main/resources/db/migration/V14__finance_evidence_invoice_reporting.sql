-- F05 provider-neutral evidence, invoice, Procurement and reporting vertical.
-- F02-F04 tables are upstream facts and are referenced read-only.

ALTER TABLE engagements
    ADD COLUMN finance_organization_id UUID REFERENCES organizations(id);
UPDATE engagements
SET finance_organization_id = procurement_organization_id
WHERE finance_organization_id IS NULL;
CREATE OR REPLACE FUNCTION f05_default_finance_organization()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.finance_organization_id := COALESCE(
        NEW.finance_organization_id, NEW.procurement_organization_id);
    RETURN NEW;
END;
$$;
CREATE TRIGGER f05_engagement_finance_organization_default
BEFORE INSERT OR UPDATE OF procurement_organization_id,
    finance_organization_id
ON engagements
FOR EACH ROW EXECUTE FUNCTION f05_default_finance_organization();
INSERT INTO permissions (id, code, description) VALUES
    ('10000000-0000-0000-0000-000000000050', 'finance.read', 'Read scoped finance evidence and reporting facts'),
    ('10000000-0000-0000-0000-000000000051', 'evidence.package.generate', 'Generate immutable evidence package versions'),
    ('10000000-0000-0000-0000-000000000052', 'evidence.package.download', 'Request a scoped package download'),
    ('10000000-0000-0000-0000-000000000053', 'invoice.manage', 'Create and version scoped vendor invoices'),
    ('10000000-0000-0000-0000-000000000054', 'invoice.submit', 'Submit an eligible invoice to Procurement'),
    ('10000000-0000-0000-0000-000000000055', 'procurement.review', 'Record scoped Procurement review decisions'),
    ('10000000-0000-0000-0000-000000000056', 'procurement.exception', 'Accept disclosed rule-level Procurement exceptions'),
    ('10000000-0000-0000-0000-000000000057', 'payment.update', 'Append AP payment status facts'),
    ('10000000-0000-0000-0000-000000000058', 'report.export', 'Create scoped asynchronous report exports'),
    ('10000000-0000-0000-0000-000000000059', 'finance.audit.read', 'Read restricted F05 audit facts'),
    ('10000000-0000-0000-0000-000000000060', 'artifact.legal-hold.manage', 'Apply or release a scoped artifact legal hold');

INSERT INTO roles (id, code, name, description) VALUES
    ('11000000-0000-0000-0000-000000000007', 'PROCUREMENT_REVIEWER', 'Procurement reviewer', 'Scoped Procurement review authority'),
    ('11000000-0000-0000-0000-000000000008', 'FINANCE_AP', 'Finance AP', 'Scoped AP status authority'),
    ('11000000-0000-0000-0000-000000000009', 'GOVERNANCE_REVIEWER', 'Governance reviewer', 'Scoped disclosure and audit authority')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT role.id, permission.id
FROM roles role
CROSS JOIN permissions permission
WHERE
    (role.code IN ('ORG_ADMIN', 'ENGAGEMENT_ADMIN')
      AND permission.code IN (
        'finance.read', 'evidence.package.generate',
        'evidence.package.download', 'report.export', 'finance.audit.read',
        'artifact.legal-hold.manage'
      ))
    OR (role.code = 'VENDOR_MANAGER'
      AND permission.code IN (
        'finance.read', 'evidence.package.generate',
        'evidence.package.download', 'invoice.manage', 'invoice.submit',
        'report.export'
      ))
    OR (role.code = 'PROCUREMENT_REVIEWER'
      AND permission.code IN (
        'finance.read', 'evidence.package.download', 'procurement.review',
        'procurement.exception', 'report.export'
      ))
    OR (role.code = 'FINANCE_AP'
      AND permission.code IN (
        'finance.read', 'evidence.package.download', 'payment.update',
        'report.export'
      ))
    OR (role.code = 'GOVERNANCE_REVIEWER'
      AND permission.code IN (
        'finance.read', 'evidence.package.download',
        'procurement.exception', 'report.export', 'finance.audit.read',
        'artifact.legal-hold.manage'
      ))
    OR (role.code = 'AUDITOR_READONLY'
      AND permission.code IN ('finance.read', 'finance.audit.read'))
ON CONFLICT DO NOTHING;

CREATE TABLE f05_policy_versions (
    id UUID PRIMARY KEY,
    engagement_id UUID NOT NULL REFERENCES engagements(id),
    version INTEGER NOT NULL CHECK (version > 0),
    policy JSONB NOT NULL,
    manifest_schema VARCHAR(80) NOT NULL DEFAULT 'f05-evidence-manifest-v1',
    contract_version VARCHAR(80) NOT NULL DEFAULT 'certification.confirmation.readiness.v1',
    effective_from TIMESTAMPTZ NOT NULL,
    effective_to TIMESTAMPTZ,
    created_by_subject VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (engagement_id, version),
    CHECK (effective_to IS NULL OR effective_to > effective_from)
);

CREATE TABLE f05_handoff_consumptions (
    id UUID PRIMARY KEY,
    handoff_id UUID NOT NULL UNIQUE REFERENCES f05_certification_handoffs(id),
    engagement_month_id UUID NOT NULL REFERENCES engagement_months(id),
    contract_version VARCHAR(80) NOT NULL,
    source_hash VARCHAR(64) NOT NULL CHECK (source_hash ~ '^[0-9a-f]{64}$'),
    consumed_by_subject VARCHAR(255) NOT NULL,
    consumed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    correlation_id UUID NOT NULL
);

CREATE TABLE f05_private_artifacts (
    id UUID PRIMARY KEY,
    engagement_month_id UUID NOT NULL REFERENCES engagement_months(id),
    owner_organization_id UUID NOT NULL REFERENCES organizations(id),
    logical_type VARCHAR(48) NOT NULL,
    safe_name VARCHAR(255) NOT NULL,
    media_type VARCHAR(160) NOT NULL,
    byte_size BIGINT NOT NULL CHECK (byte_size >= 0),
    content_hash VARCHAR(64) NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    object_key VARCHAR(700) NOT NULL UNIQUE,
    object_version VARCHAR(255) NOT NULL,
    classification VARCHAR(32) NOT NULL CHECK (
        classification IN ('INTERNAL', 'CONFIDENTIAL', 'RESTRICTED')
    ),
    retention_class VARCHAR(64) NOT NULL,
    legal_hold BOOLEAN NOT NULL DEFAULT FALSE,
    scan_status VARCHAR(24) NOT NULL CHECK (
        scan_status IN ('PENDING', 'PASSED', 'FAILED', 'UNKNOWN', 'QUARANTINED')
    ),
    scan_engine VARCHAR(80),
    scan_reason_code VARCHAR(100),
    scanned_at TIMESTAMPTZ,
    provider_status VARCHAR(24) NOT NULL CHECK (
        provider_status IN ('NOT_CONFIGURED', 'LOCAL_METADATA_ONLY', 'CONFIGURED')
    ),
    source VARCHAR(48) NOT NULL,
    represented_at TIMESTAMPTZ,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    uploaded_by_subject VARCHAR(255) NOT NULL,
    supersedes_id UUID REFERENCES f05_private_artifacts(id),
    correlation_id UUID NOT NULL,
    UNIQUE (id, engagement_month_id),
    UNIQUE (owner_organization_id, content_hash, object_version)
);

-- Provider-neutral local/private storage adapter. Production deployments can
-- replace the adapter while retaining this metadata and integrity contract.
CREATE TABLE f05_private_artifact_blobs (
    artifact_id UUID PRIMARY KEY REFERENCES f05_private_artifacts(id),
    content BYTEA NOT NULL,
    stored_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- A hold update is valid only when its authorization record was created in
-- the same database transaction. This keeps ordinary SQL writers from
-- bypassing the service authorization/audit boundary.
CREATE TABLE f05_artifact_hold_transitions (
    id UUID PRIMARY KEY,
    artifact_id UUID NOT NULL REFERENCES f05_private_artifacts(id),
    prior_legal_hold BOOLEAN NOT NULL,
    legal_hold BOOLEAN NOT NULL,
    reason_code VARCHAR(100) NOT NULL,
    authority_snapshot JSONB NOT NULL,
    actor_subject VARCHAR(255) NOT NULL,
    correlation_id UUID NOT NULL,
    database_transaction_id BIGINT NOT NULL DEFAULT txid_current(),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    applied_at TIMESTAMPTZ,
    CHECK (prior_legal_hold <> legal_hold)
);
CREATE INDEX idx_f05_artifact_hold_transition
    ON f05_artifact_hold_transitions(
        artifact_id, database_transaction_id, recorded_at DESC);

CREATE TABLE evidence_package_versions (
    id UUID PRIMARY KEY,
    engagement_month_id UUID NOT NULL REFERENCES engagement_months(id),
    handoff_id UUID NOT NULL REFERENCES f05_certification_handoffs(id),
    policy_version_id UUID REFERENCES f05_policy_versions(id),
    invoice_id UUID NOT NULL,
    invoice_version INTEGER NOT NULL CHECK (invoice_version > 0),
    invoice_document_artifact_id UUID NOT NULL
        REFERENCES f05_private_artifacts(id),
    invoice_document_hash VARCHAR(64) NOT NULL
        CHECK (invoice_document_hash ~ '^[0-9a-f]{64}$'),
    version INTEGER NOT NULL CHECK (version > 0),
    status VARCHAR(24) NOT NULL CHECK (
        status IN ('GENERATING', 'CURRENT', 'SUPERSEDED', 'FAILED', 'INVALIDATED')
    ),
    canonical_manifest JSONB NOT NULL,
    canonical_input_hash VARCHAR(64) NOT NULL
        CHECK (canonical_input_hash ~ '^[0-9a-f]{64}$'),
    hash_algorithm VARCHAR(16) NOT NULL DEFAULT 'SHA-256'
        CHECK (hash_algorithm = 'SHA-256'),
    hash_schema_version INTEGER NOT NULL CHECK (hash_schema_version > 0),
    render_version VARCHAR(64) NOT NULL,
    supersedes_id UUID REFERENCES evidence_package_versions(id),
    invalidation_reason VARCHAR(120),
    generated_by_subject VARCHAR(255) NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    correlation_id UUID NOT NULL,
    UNIQUE (id, engagement_month_id),
    UNIQUE (engagement_month_id, version),
    UNIQUE (engagement_month_id, canonical_input_hash),
    CHECK (
        (status IN ('INVALIDATED', 'SUPERSEDED') AND invalidation_reason IS NOT NULL)
        OR status NOT IN ('INVALIDATED', 'SUPERSEDED')
    )
);
CREATE UNIQUE INDEX uq_f05_current_package
    ON evidence_package_versions(engagement_month_id)
    WHERE status = 'CURRENT';

CREATE TABLE evidence_package_items (
    id UUID PRIMARY KEY,
    package_version_id UUID NOT NULL REFERENCES evidence_package_versions(id),
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    item_type VARCHAR(80) NOT NULL,
    source_object_type VARCHAR(80) NOT NULL,
    source_object_id UUID NOT NULL,
    source_version VARCHAR(80) NOT NULL,
    source_hash VARCHAR(64) NOT NULL CHECK (source_hash ~ '^[0-9a-f]{64}$'),
    provenance VARCHAR(80) NOT NULL,
    represented_at TIMESTAMPTZ,
    recorded_at TIMESTAMPTZ NOT NULL,
    disclosure VARCHAR(400),
    artifact_id UUID REFERENCES f05_private_artifacts(id),
    safe_name VARCHAR(255) NOT NULL,
    media_type VARCHAR(120) NOT NULL,
    byte_size BIGINT NOT NULL CHECK (byte_size >= 0),
    object_version VARCHAR(160) NOT NULL,
    classification VARCHAR(32) NOT NULL CHECK (
        classification IN ('INTERNAL', 'CONFIDENTIAL', 'RESTRICTED')
    ),
    retention_class VARCHAR(80) NOT NULL,
    artifact_availability VARCHAR(48) NOT NULL CHECK (
        artifact_availability IN (
            'PRIVATE_SCAN_PASSED_BINARY',
            'IMMUTABLE_SOURCE_REFERENCE_ONLY'
        )
    ),
    UNIQUE (package_version_id, ordinal),
    UNIQUE (package_version_id, source_object_type, source_object_id, source_version)
);

CREATE TABLE evidence_package_outputs (
    id UUID PRIMARY KEY,
    package_version_id UUID NOT NULL REFERENCES evidence_package_versions(id),
    output_format VARCHAR(16) NOT NULL CHECK (
        output_format IN ('JSON', 'PDF', 'CSV', 'XLSX', 'ZIP')
    ),
    artifact_id UUID REFERENCES f05_private_artifacts(id),
    output_hash VARCHAR(64) NOT NULL CHECK (output_hash ~ '^[0-9a-f]{64}$'),
    renderer_status VARCHAR(24) NOT NULL CHECK (
        renderer_status IN ('NOT_CONFIGURED', 'LOCAL_MANIFEST_ONLY', 'RENDERED')
    ),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (package_version_id, output_format)
);

CREATE TABLE evidence_package_shares (
    id UUID PRIMARY KEY,
    package_version_id UUID NOT NULL REFERENCES evidence_package_versions(id),
    recipient_subject VARCHAR(255) NOT NULL,
    access_scope VARCHAR(16) NOT NULL CHECK (access_scope IN ('VIEW', 'DOWNLOAD')),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    revoked_by_subject VARCHAR(255),
    created_by_subject VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    correlation_id UUID NOT NULL,
    CHECK (expires_at > created_at),
    CHECK (
        (revoked_at IS NULL AND revoked_by_subject IS NULL)
        OR (revoked_at IS NOT NULL AND revoked_by_subject IS NOT NULL)
    )
);
CREATE UNIQUE INDEX uq_f05_active_package_share
    ON evidence_package_shares(package_version_id, recipient_subject, access_scope)
    WHERE revoked_at IS NULL;

CREATE TABLE invoices (
    id UUID PRIMARY KEY,
    engagement_month_id UUID NOT NULL REFERENCES engagement_months(id),
    vendor_organization_id UUID NOT NULL REFERENCES organizations(id),
    invoice_type VARCHAR(24) NOT NULL CHECK (
        invoice_type IN ('PRIMARY', 'CORRECTION', 'CREDIT_NOTE', 'DEBIT_NOTE')
    ),
    invoice_number VARCHAR(160) NOT NULL,
    normalized_invoice_number VARCHAR(160) NOT NULL,
    invoice_date DATE NOT NULL,
    billing_period_start DATE NOT NULL,
    billing_period_end DATE NOT NULL,
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    taxable_value NUMERIC(20, 4),
    tax_value NUMERIC(20, 4),
    total_value NUMERIC(20, 4),
    po_reference VARCHAR(160),
    work_order_reference VARCHAR(160),
    status VARCHAR(40) NOT NULL CHECK (
        status IN (
            'DRAFT', 'UPLOADED', 'EVIDENCE_PENDING',
            'READY_FOR_VENDOR_SUBMISSION', 'SUBMITTED_TO_PROCUREMENT',
            'PROCUREMENT_REVIEW', 'APPROVED_FOR_PROCESSING',
            'CHANGES_REQUESTED', 'ON_HOLD', 'REJECTED',
            'PAYMENT_INITIATED', 'PAID', 'CLOSED', 'SUPERSEDED',
            'CANCELLED', 'EXCEPTION_ACCEPTED'
        )
    ),
    current_version INTEGER NOT NULL DEFAULT 1 CHECK (current_version > 0),
    optimistic_version BIGINT NOT NULL DEFAULT 1 CHECK (optimistic_version > 0),
    corrected_invoice_id UUID REFERENCES invoices(id),
    note_for_invoice_id UUID REFERENCES invoices(id),
    current_package_version_id UUID REFERENCES evidence_package_versions(id),
    current_readiness_run_id UUID,
    created_by_subject VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    correlation_id UUID NOT NULL,
    UNIQUE (id, engagement_month_id),
    CHECK (billing_period_end >= billing_period_start),
    CHECK (
        (invoice_type = 'CORRECTION' AND corrected_invoice_id IS NOT NULL)
        OR (invoice_type IN ('CREDIT_NOTE', 'DEBIT_NOTE') AND note_for_invoice_id IS NOT NULL)
        OR (invoice_type = 'PRIMARY' AND corrected_invoice_id IS NULL AND note_for_invoice_id IS NULL)
    )
);
CREATE UNIQUE INDEX uq_f05_active_invoice_number
    ON invoices(vendor_organization_id, normalized_invoice_number)
    WHERE status NOT IN ('SUPERSEDED', 'CANCELLED');
CREATE UNIQUE INDEX uq_f05_primary_invoice_per_month
    ON invoices(engagement_month_id)
    WHERE invoice_type = 'PRIMARY' AND status NOT IN ('SUPERSEDED', 'CANCELLED');
CREATE INDEX idx_f05_invoice_month_status
    ON invoices(engagement_month_id, status, updated_at DESC);

CREATE TABLE invoice_versions (
    id UUID PRIMARY KEY,
    invoice_id UUID NOT NULL REFERENCES invoices(id),
    version INTEGER NOT NULL CHECK (version > 0),
    document_artifact_id UUID REFERENCES f05_private_artifacts(id),
    metadata_manifest JSONB NOT NULL,
    metadata_hash VARCHAR(64) NOT NULL CHECK (metadata_hash ~ '^[0-9a-f]{64}$'),
    source VARCHAR(48) NOT NULL,
    represented_at TIMESTAMPTZ,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_subject VARCHAR(255) NOT NULL,
    supersedes_id UUID REFERENCES invoice_versions(id),
    correlation_id UUID NOT NULL,
    UNIQUE (invoice_id, version),
    UNIQUE (invoice_id, metadata_hash)
);
ALTER TABLE evidence_package_versions
    ADD CONSTRAINT fk_f05_package_invoice_scope
    FOREIGN KEY (invoice_id, engagement_month_id)
    REFERENCES invoices(id, engagement_month_id);

CREATE TABLE invoice_readiness_runs (
    id UUID PRIMARY KEY,
    invoice_id UUID NOT NULL REFERENCES invoices(id),
    invoice_version INTEGER NOT NULL,
    package_version_id UUID REFERENCES evidence_package_versions(id),
    handoff_id UUID NOT NULL REFERENCES f05_certification_handoffs(id),
    input_manifest JSONB NOT NULL,
    input_hash VARCHAR(64) NOT NULL CHECK (input_hash ~ '^[0-9a-f]{64}$'),
    policy_version VARCHAR(80) NOT NULL,
    overall_status VARCHAR(64) NOT NULL CHECK (
        overall_status IN (
            'PASS', 'PASS_WITH_DISCLOSED_NON_BLOCKING_EXCEPTION',
            'BLOCKED_MISSING_EVIDENCE', 'BLOCKED_INVALID_VERSION',
            'BLOCKED_CONFIRMATION_PENDING', 'BLOCKED_REOPEN_OR_CORRECTION',
            'EXCEPTION_ACCEPTED_BY_PROCUREMENT'
        )
    ),
    eligible BOOLEAN NOT NULL,
    current_result BOOLEAN NOT NULL DEFAULT TRUE,
    evaluated_by_subject VARCHAR(255) NOT NULL,
    evaluated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    invalidated_at TIMESTAMPTZ,
    correlation_id UUID NOT NULL,
    UNIQUE (invoice_id, input_hash),
    UNIQUE (id, invoice_id)
);
ALTER TABLE invoices
    ADD CONSTRAINT fk_f05_invoice_readiness
    FOREIGN KEY (current_readiness_run_id, id)
    REFERENCES invoice_readiness_runs(id, invoice_id);
CREATE UNIQUE INDEX uq_f05_current_readiness
    ON invoice_readiness_runs(invoice_id)
    WHERE current_result;

CREATE TABLE invoice_readiness_results (
    id UUID PRIMARY KEY,
    readiness_run_id UUID NOT NULL REFERENCES invoice_readiness_runs(id),
    rule_code VARCHAR(80) NOT NULL,
    result VARCHAR(64) NOT NULL CHECK (
        result IN (
            'PASS', 'PASS_WITH_DISCLOSED_NON_BLOCKING_EXCEPTION',
            'BLOCKED_MISSING_EVIDENCE', 'BLOCKED_INVALID_VERSION',
            'BLOCKED_CONFIRMATION_PENDING', 'BLOCKED_REOPEN_OR_CORRECTION',
            'EXCEPTION_ACCEPTED_BY_PROCUREMENT'
        )
    ),
    severity VARCHAR(16) NOT NULL CHECK (
        severity IN ('INFORMATION', 'WARNING', 'BLOCKING')
    ),
    owner_label VARCHAR(120) NOT NULL,
    source_object_type VARCHAR(80),
    source_object_id UUID,
    source_version VARCHAR(80),
    source_hash VARCHAR(64),
    freshness_at TIMESTAMPTZ,
    remediation_cta VARCHAR(255),
    UNIQUE (readiness_run_id, rule_code),
    CHECK (source_hash IS NULL OR source_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE procurement_reviews (
    id UUID PRIMARY KEY,
    invoice_id UUID NOT NULL REFERENCES invoices(id),
    invoice_version INTEGER NOT NULL,
    package_version_id UUID NOT NULL REFERENCES evidence_package_versions(id),
    readiness_run_id UUID NOT NULL REFERENCES invoice_readiness_runs(id),
    decision VARCHAR(40) NOT NULL CHECK (
        decision IN (
            'APPROVED_FOR_PROCESSING', 'CHANGES_REQUESTED',
            'ON_HOLD', 'REJECTED', 'EXCEPTION_REQUESTED'
        )
    ),
    category VARCHAR(80),
    comment VARCHAR(1000),
    authority_snapshot JSONB NOT NULL,
    reviewed_by_subject VARCHAR(255) NOT NULL,
    reviewed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    correlation_id UUID NOT NULL,
    CHECK (
        decision = 'APPROVED_FOR_PROCESSING'
        OR (category IS NOT NULL AND comment IS NOT NULL)
    )
);

CREATE TABLE procurement_exceptions (
    id UUID PRIMARY KEY,
    review_id UUID NOT NULL UNIQUE REFERENCES procurement_reviews(id),
    invoice_id UUID NOT NULL REFERENCES invoices(id),
    invoice_version INTEGER NOT NULL CHECK (invoice_version > 0),
    package_version_id UUID NOT NULL REFERENCES evidence_package_versions(id),
    package_version INTEGER NOT NULL CHECK (package_version > 0),
    readiness_run_id UUID NOT NULL REFERENCES invoice_readiness_runs(id),
    readiness_result_id UUID NOT NULL REFERENCES invoice_readiness_results(id),
    policy_version_id UUID NOT NULL REFERENCES f05_policy_versions(id),
    policy_version INTEGER NOT NULL CHECK (policy_version > 0),
    rationale VARCHAR(1000) NOT NULL,
    valid_until TIMESTAMPTZ NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (
        status IN (
            'PENDING_ACTIVATION', 'PENDING_SECOND_APPROVAL',
            'ACCEPTED', 'EXPIRED')
    ),
    second_approval_required BOOLEAN NOT NULL,
    request_authority_snapshot JSONB NOT NULL,
    requested_by_subject VARCHAR(255) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    second_approver_subject VARCHAR(255),
    second_approval_authority_snapshot JSONB,
    second_approved_at TIMESTAMPTZ,
    accepted_readiness_run_id UUID REFERENCES invoice_readiness_runs(id),
    expired_at TIMESTAMPTZ,
    correlation_id UUID NOT NULL,
    UNIQUE (id, invoice_id),
    CHECK (valid_until > requested_at),
    CHECK (
        (status = 'PENDING_ACTIVATION'
         AND NOT second_approval_required
         AND second_approver_subject IS NULL
         AND second_approval_authority_snapshot IS NULL
         AND second_approved_at IS NULL
         AND accepted_readiness_run_id IS NULL
         AND expired_at IS NULL)
        OR
        (status = 'PENDING_SECOND_APPROVAL'
         AND second_approval_required
         AND second_approver_subject IS NULL
         AND second_approval_authority_snapshot IS NULL
         AND second_approved_at IS NULL
         AND accepted_readiness_run_id IS NULL
         AND expired_at IS NULL)
        OR
        (status = 'ACCEPTED'
         AND accepted_readiness_run_id IS NOT NULL
         AND expired_at IS NULL
         AND (
             (second_approval_required
              AND second_approver_subject IS NOT NULL
              AND second_approver_subject <> requested_by_subject
              AND second_approval_authority_snapshot IS NOT NULL
              AND second_approved_at IS NOT NULL)
             OR
             (NOT second_approval_required
              AND second_approver_subject IS NULL
              AND second_approval_authority_snapshot IS NULL
              AND second_approved_at IS NULL)
         ))
        OR
        (status = 'EXPIRED' AND expired_at IS NOT NULL)
    )
);
CREATE UNIQUE INDEX uq_f05_open_exception_per_invoice
    ON procurement_exceptions(invoice_id)
    WHERE status IN (
        'PENDING_ACTIVATION', 'PENDING_SECOND_APPROVAL', 'ACCEPTED');

CREATE OR REPLACE FUNCTION enforce_f05_exception_binding()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM procurement_reviews review
        JOIN invoices invoice ON invoice.id = NEW.invoice_id
        JOIN engagement_months month
          ON month.id = invoice.engagement_month_id
        JOIN evidence_package_versions package
          ON package.id = NEW.package_version_id
        JOIN invoice_readiness_runs readiness
          ON readiness.id = NEW.readiness_run_id
        JOIN invoice_readiness_results result
          ON result.id = NEW.readiness_result_id
        JOIN f05_policy_versions policy
          ON policy.id = NEW.policy_version_id
        WHERE review.id = NEW.review_id
          AND review.invoice_id = NEW.invoice_id
          AND review.invoice_version = NEW.invoice_version
          AND review.package_version_id = NEW.package_version_id
          AND review.readiness_run_id = NEW.readiness_run_id
          AND review.decision = 'EXCEPTION_REQUESTED'
          AND review.reviewed_by_subject = NEW.requested_by_subject
          AND package.invoice_id = NEW.invoice_id
          AND package.invoice_version = NEW.invoice_version
          AND package.version = NEW.package_version
          AND package.status = 'CURRENT'
          AND readiness.invoice_id = NEW.invoice_id
          AND readiness.invoice_version = NEW.invoice_version
          AND readiness.package_version_id = NEW.package_version_id
          AND readiness.current_result
          AND result.readiness_run_id = NEW.readiness_run_id
          AND result.result NOT IN (
              'PASS', 'PASS_WITH_DISCLOSED_NON_BLOCKING_EXCEPTION',
              'EXCEPTION_ACCEPTED_BY_PROCUREMENT')
          AND policy.engagement_id = month.engagement_id
          AND policy.version = NEW.policy_version
          AND policy.effective_from <= NEW.requested_at
          AND (
              policy.effective_to IS NULL
              OR policy.effective_to > NEW.requested_at
          )
    ) THEN
        RAISE EXCEPTION
            'Procurement exception binding is invalid or cross-scope'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f05_exception_binding_gate
BEFORE INSERT ON procurement_exceptions
FOR EACH ROW EXECUTE FUNCTION enforce_f05_exception_binding();

CREATE TABLE procurement_queries (
    id UUID PRIMARY KEY,
    review_id UUID NOT NULL REFERENCES procurement_reviews(id),
    invoice_id UUID NOT NULL REFERENCES invoices(id),
    category VARCHAR(80) NOT NULL,
    owner_subject VARCHAR(255) NOT NULL,
    due_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (
        status IN ('OPEN', 'RESPONDED', 'CLOSED', 'CANCELLED')
    ),
    closed_by_subject VARCHAR(255),
    closed_at TIMESTAMPTZ,
    close_reason VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    correlation_id UUID NOT NULL,
    UNIQUE (review_id, category)
);

CREATE TABLE procurement_query_responses (
    id UUID PRIMARY KEY,
    query_id UUID NOT NULL REFERENCES procurement_queries(id),
    response_text VARCHAR(2000) NOT NULL,
    response_artifact_id UUID REFERENCES f05_private_artifacts(id),
    responded_by_subject VARCHAR(255) NOT NULL,
    responded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE payment_status_history (
    id UUID PRIMARY KEY,
    invoice_id UUID NOT NULL REFERENCES invoices(id),
    sequence_number INTEGER NOT NULL CHECK (sequence_number > 0),
    status VARCHAR(32) NOT NULL CHECK (
        status IN (
            'NOT_SUBMITTED', 'SUBMITTED_TO_AP', 'VALIDATION_IN_PROGRESS',
            'PAYMENT_SCHEDULED', 'PAYMENT_INITIATED', 'PAID',
            'PAYMENT_FAILED', 'ON_HOLD'
        )
    ),
    sanitized_comment VARCHAR(500),
    internal_comment VARCHAR(500),
    external_reference VARCHAR(160),
    status_at TIMESTAMPTZ NOT NULL,
    expected_payment_date DATE,
    actual_payment_date DATE,
    source VARCHAR(32) NOT NULL CHECK (source IN ('MANUAL', 'AP', 'ERP')),
    recorded_by_subject VARCHAR(255) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    correlation_id UUID NOT NULL,
    UNIQUE (invoice_id, sequence_number),
    UNIQUE (source, external_reference)
);

CREATE TABLE f05_report_exports (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    engagement_id UUID REFERENCES engagements(id),
    report_code VARCHAR(80) NOT NULL,
    report_version VARCHAR(32) NOT NULL,
    format VARCHAR(16) NOT NULL CHECK (format IN ('CSV', 'XLSX', 'PDF', 'JSON')),
    filters JSONB NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (
        status IN ('PENDING', 'CLAIMED', 'READY', 'FAILED', 'DEAD_LETTER', 'EXPIRED')
    ),
    progress INTEGER NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    retry_cycle_attempt_count INTEGER NOT NULL DEFAULT 0
        CHECK (retry_cycle_attempt_count >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_owner VARCHAR(160),
    lease_expires_at TIMESTAMPTZ,
    last_error_code VARCHAR(80),
    row_count BIGINT,
    result_artifact_id UUID REFERENCES f05_private_artifacts(id),
    result_hash VARCHAR(64),
    source_freshness_at TIMESTAMPTZ,
    snapshot_label VARCHAR(32) NOT NULL DEFAULT 'CURRENT',
    requested_by_subject VARCHAR(255) NOT NULL,
    authority_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    correlation_id UUID NOT NULL,
    CHECK (result_hash IS NULL OR result_hash ~ '^[0-9a-f]{64}$'),
    CHECK (
        (lease_owner IS NULL AND lease_expires_at IS NULL)
        OR (lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)
    )
);

CREATE TABLE f05_operation_jobs (
    id UUID PRIMARY KEY,
    job_type VARCHAR(32) NOT NULL CHECK (
        job_type IN ('PACKAGE', 'READINESS', 'EXPORT', 'RETENTION')
    ),
    aggregate_id UUID NOT NULL,
    engagement_month_id UUID,
    status VARCHAR(24) NOT NULL CHECK (
        status IN ('PENDING', 'CLAIMED', 'READY', 'RETRY_SCHEDULED',
                   'FAILED', 'DEAD_LETTER', 'CANCELLED')
    ),
    progress INTEGER NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMPTZ,
    lease_owner VARCHAR(160),
    lease_expires_at TIMESTAMPTZ,
    checkpoint JSONB NOT NULL DEFAULT '{}'::jsonb,
    last_error_code VARCHAR(80),
    correlation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    UNIQUE (job_type, aggregate_id),
    CHECK (
        (lease_owner IS NULL AND lease_expires_at IS NULL)
        OR (lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)
    )
);
CREATE INDEX idx_f05_operation_jobs_due
    ON f05_operation_jobs(status, next_attempt_at, created_at);

CREATE TABLE f05_idempotency_keys (
    actor_subject VARCHAR(255) NOT NULL,
    operation VARCHAR(80) NOT NULL,
    scope_id UUID NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_hash VARCHAR(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    result_type VARCHAR(80) NOT NULL,
    result_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (actor_subject, operation, scope_id, idempotency_key)
);

CREATE TABLE f05_domain_events (
    id UUID PRIMARY KEY,
    engagement_month_id UUID,
    event_type VARCHAR(120) NOT NULL,
    aggregate_type VARCHAR(80) NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL CHECK (aggregate_version > 0),
    payload JSONB NOT NULL,
    actor_subject VARCHAR(255) NOT NULL,
    correlation_id UUID NOT NULL,
    causation_id UUID,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (aggregate_type, aggregate_id, aggregate_version, event_type)
);

CREATE TABLE f05_outbox (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE REFERENCES f05_domain_events(id),
    status VARCHAR(24) NOT NULL CHECK (
        status IN ('PENDING', 'CLAIMED', 'PUBLISHED', 'DEAD_LETTER', 'CANCELLED')
    ),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMPTZ,
    lease_owner VARCHAR(160),
    lease_expires_at TIMESTAMPTZ,
    last_error_code VARCHAR(80),
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (
        (lease_owner IS NULL AND lease_expires_at IS NULL)
        OR (lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)
    )
);
CREATE INDEX idx_f05_outbox_due
    ON f05_outbox(next_attempt_at, created_at)
    WHERE status IN ('PENDING', 'CLAIMED');

CREATE TABLE f05_audit_events (
    id UUID PRIMARY KEY,
    engagement_month_id UUID,
    action VARCHAR(100) NOT NULL,
    object_type VARCHAR(80) NOT NULL,
    object_id UUID NOT NULL,
    object_version BIGINT,
    result VARCHAR(32) NOT NULL,
    reason_code VARCHAR(100),
    authority_snapshot JSONB NOT NULL,
    evidence_references JSONB NOT NULL DEFAULT '[]'::jsonb,
    actor_subject VARCHAR(255) NOT NULL,
    correlation_id UUID NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_f05_audit_object
    ON f05_audit_events(object_type, object_id, recorded_at DESC);

CREATE TABLE f05_security_events (
    id UUID PRIMARY KEY,
    engagement_month_id UUID,
    event_type VARCHAR(100) NOT NULL,
    result VARCHAR(32) NOT NULL,
    reason_code VARCHAR(100) NOT NULL,
    actor_subject_hash VARCHAR(64) NOT NULL CHECK (actor_subject_hash ~ '^[0-9a-f]{64}$'),
    correlation_id UUID NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE f05_rate_limit_buckets (
    actor_subject_hash VARCHAR(64) NOT NULL
        CHECK (actor_subject_hash ~ '^[0-9a-f]{64}$'),
    client_address_hash VARCHAR(64) NOT NULL
        CHECK (client_address_hash ~ '^[0-9a-f]{64}$'),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    operation VARCHAR(48) NOT NULL,
    bucket_start TIMESTAMPTZ NOT NULL,
    request_count INTEGER NOT NULL CHECK (request_count > 0),
    PRIMARY KEY (
        actor_subject_hash, client_address_hash,
        organization_id, operation, bucket_start
    )
);

CREATE TABLE f05_invalidation_effects (
    id UUID PRIMARY KEY,
    handoff_invalidation_id UUID NOT NULL UNIQUE REFERENCES f05_handoff_invalidations(id),
    engagement_month_id UUID NOT NULL REFERENCES engagement_months(id),
    reason_code VARCHAR(100) NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    correlation_id UUID NOT NULL
);

CREATE TABLE f05_metric_dictionary (
    metric_code VARCHAR(80) NOT NULL,
    version INTEGER NOT NULL CHECK (version > 0),
    display_name VARCHAR(160) NOT NULL,
    definition TEXT NOT NULL,
    source_label VARCHAR(160) NOT NULL,
    timezone_semantics VARCHAR(80) NOT NULL,
    freshness_semantics VARCHAR(160) NOT NULL,
    empty_semantics VARCHAR(160) NOT NULL,
    PRIMARY KEY (metric_code, version)
);
INSERT INTO f05_metric_dictionary VALUES
    ('INVOICE_READINESS', 1, 'Invoice readiness', 'Current mandatory readiness rules that pass or carry an explicit disclosed exception.', 'F04 handoff, package and invoice version', 'Engagement timezone', 'Current input hash and evaluation time are displayed.', 'Zero means evaluated with no eligible invoice; unavailable means no evaluation.'),
    ('CONFIRMATION_COMPLETION', 1, 'Confirmation completion', 'Effective verified F04 confirmation handoffs, distinct from downstream exception decisions.', 'Effective F04 handoff', 'Engagement timezone', 'Invalidated handoffs are stale immediately.', 'Unavailable means no effective handoff.'),
    ('PAYMENT_STATUS', 1, 'Payment status', 'Latest append-only AP or ERP status fact.', 'Payment status history', 'Status source timestamp', 'Latest recorded sequence is displayed.', 'Unavailable means no payment status fact.');

CREATE OR REPLACE FUNCTION f05_reject_immutable_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'F05 historical record is immutable'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER f05_package_items_immutable
BEFORE UPDATE OR DELETE ON evidence_package_items
FOR EACH ROW EXECUTE FUNCTION f05_reject_immutable_change();
CREATE TRIGGER f05_package_outputs_immutable
BEFORE UPDATE OR DELETE ON evidence_package_outputs
FOR EACH ROW EXECUTE FUNCTION f05_reject_immutable_change();
CREATE TRIGGER f05_invoice_versions_immutable
BEFORE UPDATE OR DELETE ON invoice_versions
FOR EACH ROW EXECUTE FUNCTION f05_reject_immutable_change();
CREATE TRIGGER f05_readiness_results_immutable
BEFORE UPDATE OR DELETE ON invoice_readiness_results
FOR EACH ROW EXECUTE FUNCTION f05_reject_immutable_change();
CREATE TRIGGER f05_reviews_immutable
BEFORE UPDATE OR DELETE ON procurement_reviews
FOR EACH ROW EXECUTE FUNCTION f05_reject_immutable_change();
CREATE OR REPLACE FUNCTION f05_guard_exception_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE'
       OR OLD.id <> NEW.id
       OR OLD.review_id <> NEW.review_id
       OR OLD.invoice_id <> NEW.invoice_id
       OR OLD.invoice_version <> NEW.invoice_version
       OR OLD.package_version_id <> NEW.package_version_id
       OR OLD.package_version <> NEW.package_version
       OR OLD.readiness_run_id <> NEW.readiness_run_id
       OR OLD.readiness_result_id <> NEW.readiness_result_id
       OR OLD.policy_version_id <> NEW.policy_version_id
       OR OLD.policy_version <> NEW.policy_version
       OR OLD.rationale <> NEW.rationale
       OR OLD.valid_until <> NEW.valid_until
       OR OLD.second_approval_required <> NEW.second_approval_required
       OR OLD.request_authority_snapshot <> NEW.request_authority_snapshot
       OR OLD.requested_by_subject <> NEW.requested_by_subject
       OR OLD.requested_at <> NEW.requested_at
       OR OLD.correlation_id <> NEW.correlation_id
       OR NOT (
           (OLD.status = 'PENDING_SECOND_APPROVAL'
            AND NEW.status = 'ACCEPTED'
            AND OLD.second_approver_subject IS NULL
            AND NEW.second_approver_subject IS NOT NULL
            AND NEW.second_approver_subject <> OLD.requested_by_subject
            AND OLD.second_approval_authority_snapshot IS NULL
            AND NEW.second_approval_authority_snapshot IS NOT NULL
            AND OLD.second_approved_at IS NULL
            AND NEW.second_approved_at IS NOT NULL
            AND OLD.accepted_readiness_run_id IS NULL
            AND NEW.accepted_readiness_run_id IS NOT NULL
            AND OLD.expired_at IS NULL
            AND NEW.expired_at IS NULL)
           OR
           (OLD.status = 'PENDING_ACTIVATION'
            AND NEW.status = 'ACCEPTED'
            AND NOT OLD.second_approval_required
            AND OLD.second_approver_subject IS NULL
            AND NEW.second_approver_subject IS NULL
            AND OLD.second_approval_authority_snapshot IS NULL
            AND NEW.second_approval_authority_snapshot IS NULL
            AND OLD.second_approved_at IS NULL
            AND NEW.second_approved_at IS NULL
            AND OLD.accepted_readiness_run_id IS NULL
            AND NEW.accepted_readiness_run_id IS NOT NULL
            AND OLD.expired_at IS NULL
            AND NEW.expired_at IS NULL)
           OR
           (OLD.status IN ('PENDING_SECOND_APPROVAL', 'ACCEPTED')
            AND NEW.status = 'EXPIRED'
            AND OLD.second_approver_subject IS NOT DISTINCT FROM
                NEW.second_approver_subject
            AND OLD.second_approval_authority_snapshot IS NOT DISTINCT FROM
                NEW.second_approval_authority_snapshot
            AND OLD.second_approved_at IS NOT DISTINCT FROM
                NEW.second_approved_at
            AND OLD.accepted_readiness_run_id IS NOT DISTINCT FROM
                NEW.accepted_readiness_run_id
            AND OLD.expired_at IS NULL
            AND NEW.expired_at IS NOT NULL)
       )
    THEN
        RAISE EXCEPTION 'F05 Procurement exception mutation is not permitted'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.status = 'ACCEPTED'
       AND NOT EXISTS (
           SELECT 1
           FROM invoice_readiness_runs readiness
           WHERE readiness.id = NEW.accepted_readiness_run_id
             AND readiness.invoice_id = NEW.invoice_id
             AND readiness.invoice_version = NEW.invoice_version
             AND readiness.package_version_id = NEW.package_version_id
             AND readiness.overall_status =
                 'EXCEPTION_ACCEPTED_BY_PROCUREMENT'
             AND readiness.eligible
             AND readiness.current_result
       )
    THEN
        RAISE EXCEPTION
            'Accepted exception readiness lineage is invalid or cross-scope'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f05_exception_transition_guard
BEFORE UPDATE OR DELETE ON procurement_exceptions
FOR EACH ROW EXECUTE FUNCTION f05_guard_exception_change();
CREATE TRIGGER f05_query_responses_immutable
BEFORE UPDATE OR DELETE ON procurement_query_responses
FOR EACH ROW EXECUTE FUNCTION f05_reject_immutable_change();

CREATE OR REPLACE FUNCTION f05_guard_query_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE'
       OR OLD.id <> NEW.id
       OR OLD.review_id <> NEW.review_id
       OR OLD.invoice_id <> NEW.invoice_id
       OR OLD.category <> NEW.category
       OR OLD.owner_subject <> NEW.owner_subject
       OR OLD.due_at <> NEW.due_at
       OR OLD.created_at <> NEW.created_at
       OR OLD.correlation_id <> NEW.correlation_id
       OR NOT (
           (OLD.status = 'OPEN' AND NEW.status IN ('RESPONDED', 'CANCELLED'))
           OR (OLD.status = 'RESPONDED' AND NEW.status IN ('CLOSED', 'CANCELLED'))
       )
    THEN
        RAISE EXCEPTION 'Illegal F05 Procurement query transition'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f05_procurement_query_guard
BEFORE UPDATE OR DELETE ON procurement_queries
FOR EACH ROW EXECUTE FUNCTION f05_guard_query_change();
CREATE TRIGGER f05_payment_history_immutable
BEFORE UPDATE OR DELETE ON payment_status_history
FOR EACH ROW EXECUTE FUNCTION f05_reject_immutable_change();
CREATE TRIGGER f05_domain_events_immutable
BEFORE UPDATE OR DELETE ON f05_domain_events
FOR EACH ROW EXECUTE FUNCTION f05_reject_immutable_change();
CREATE TRIGGER f05_audit_events_immutable
BEFORE UPDATE OR DELETE ON f05_audit_events
FOR EACH ROW EXECUTE FUNCTION f05_reject_immutable_change();
CREATE TRIGGER f05_security_events_immutable
BEFORE UPDATE OR DELETE ON f05_security_events
FOR EACH ROW EXECUTE FUNCTION f05_reject_immutable_change();
CREATE TRIGGER f05_idempotency_immutable
BEFORE UPDATE OR DELETE ON f05_idempotency_keys
FOR EACH ROW EXECUTE FUNCTION f05_reject_immutable_change();
CREATE TRIGGER f05_invalidation_effects_immutable
BEFORE UPDATE OR DELETE ON f05_invalidation_effects
FOR EACH ROW EXECUTE FUNCTION f05_reject_immutable_change();
CREATE TRIGGER f05_artifact_blobs_immutable
BEFORE UPDATE OR DELETE ON f05_private_artifact_blobs
FOR EACH ROW EXECUTE FUNCTION f05_reject_immutable_change();
CREATE OR REPLACE FUNCTION f05_guard_hold_transition_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE'
       OR OLD.id <> NEW.id
       OR OLD.artifact_id <> NEW.artifact_id
       OR OLD.prior_legal_hold <> NEW.prior_legal_hold
       OR OLD.legal_hold <> NEW.legal_hold
       OR OLD.reason_code <> NEW.reason_code
       OR OLD.authority_snapshot <> NEW.authority_snapshot
       OR OLD.actor_subject <> NEW.actor_subject
       OR OLD.correlation_id <> NEW.correlation_id
       OR OLD.database_transaction_id <> NEW.database_transaction_id
       OR OLD.recorded_at <> NEW.recorded_at
       OR OLD.applied_at IS NOT NULL
       OR NEW.applied_at IS NULL
    THEN
        RAISE EXCEPTION 'F05 artifact hold transition is immutable'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f05_artifact_hold_transitions_guard
BEFORE UPDATE OR DELETE ON f05_artifact_hold_transitions
FOR EACH ROW EXECUTE FUNCTION f05_guard_hold_transition_change();

CREATE OR REPLACE FUNCTION f05_guard_artifact_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE'
       OR OLD.id <> NEW.id
       OR OLD.engagement_month_id <> NEW.engagement_month_id
       OR OLD.owner_organization_id <> NEW.owner_organization_id
       OR OLD.logical_type <> NEW.logical_type
       OR OLD.safe_name <> NEW.safe_name
       OR OLD.media_type <> NEW.media_type
       OR OLD.byte_size <> NEW.byte_size
       OR OLD.content_hash <> NEW.content_hash
       OR OLD.object_key <> NEW.object_key
       OR OLD.object_version <> NEW.object_version
       OR OLD.classification <> NEW.classification
       OR OLD.retention_class <> NEW.retention_class
       OR OLD.provider_status <> NEW.provider_status
       OR OLD.source <> NEW.source
       OR OLD.represented_at IS DISTINCT FROM NEW.represented_at
       OR OLD.recorded_at <> NEW.recorded_at
       OR OLD.uploaded_by_subject <> NEW.uploaded_by_subject
       OR OLD.correlation_id <> NEW.correlation_id
       OR OLD.supersedes_id IS DISTINCT FROM NEW.supersedes_id
       OR (
           OLD.legal_hold <> NEW.legal_hold
           AND (
               OLD.scan_status <> NEW.scan_status
               OR NOT EXISTS (
                   SELECT 1
                   FROM f05_artifact_hold_transitions transition
                   WHERE transition.artifact_id = NEW.id
                     AND transition.prior_legal_hold = OLD.legal_hold
                     AND transition.legal_hold = NEW.legal_hold
                     AND transition.database_transaction_id = txid_current()
                     AND transition.applied_at IS NULL
               )
           )
       )
       OR NOT (
           OLD.scan_status = NEW.scan_status
           OR (
               OLD.scan_status IN ('PENDING', 'UNKNOWN')
               AND NEW.scan_status IN (
                   'PASSED', 'FAILED', 'QUARANTINED', 'UNKNOWN')
               AND NEW.scan_engine IS NOT NULL
               AND NEW.scanned_at IS NOT NULL
           )
       )
       OR (
           OLD.scan_status = NEW.scan_status
           AND (
               OLD.scan_engine IS DISTINCT FROM NEW.scan_engine
               OR OLD.scan_reason_code IS DISTINCT FROM NEW.scan_reason_code
               OR OLD.scanned_at IS DISTINCT FROM NEW.scanned_at
           )
       )
    THEN
        RAISE EXCEPTION 'F05 artifact metadata is immutable outside scan/legal-hold transition'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f05_private_artifact_guard
BEFORE UPDATE OR DELETE ON f05_private_artifacts
FOR EACH ROW EXECUTE FUNCTION f05_guard_artifact_change();

CREATE OR REPLACE FUNCTION f05_apply_artifact_hold_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.legal_hold <> NEW.legal_hold THEN
        UPDATE f05_artifact_hold_transitions
        SET applied_at = CURRENT_TIMESTAMP
        WHERE artifact_id = NEW.id
          AND prior_legal_hold = OLD.legal_hold
          AND legal_hold = NEW.legal_hold
          AND database_transaction_id = txid_current()
          AND applied_at IS NULL;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'F05 artifact hold transition was not recorded'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f05_private_artifact_hold_apply
AFTER UPDATE OF legal_hold ON f05_private_artifacts
FOR EACH ROW EXECUTE FUNCTION f05_apply_artifact_hold_transition();

CREATE OR REPLACE FUNCTION f05_record_artifact_scan_audit()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    prior_status VARCHAR(24);
BEGIN
    prior_status := CASE
        WHEN TG_OP = 'INSERT' THEN 'UNRECORDED'
        ELSE OLD.scan_status
    END;
    IF (TG_OP = 'INSERT' AND NEW.scan_status <> 'PENDING')
       OR (TG_OP = 'UPDATE' AND OLD.scan_status <> NEW.scan_status) THEN
        INSERT INTO f05_audit_events(
            id, engagement_month_id, action, object_type, object_id,
            object_version, result, reason_code, authority_snapshot,
            evidence_references, actor_subject, correlation_id
        ) VALUES (
            gen_random_uuid(), NEW.engagement_month_id,
            'ARTIFACT_SCAN_STATE_CHANGED', 'PRIVATE_ARTIFACT', NEW.id,
            NULL, 'SUCCESS',
            COALESCE(NEW.scan_reason_code, 'SCAN_COMPLETED'),
            jsonb_build_object(
                'authorityType', 'SYSTEM_SCANNER',
                'scanEngine', NEW.scan_engine,
                'source', NEW.source
            ),
            jsonb_build_array(jsonb_build_object(
                'referenceType', 'SCAN_TRANSITION',
                'referenceId', NEW.id,
                'fromStatus', prior_status,
                'toStatus', NEW.scan_status
            )),
            'system:finance-scanner', NEW.correlation_id
        );
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f05_private_artifact_scan_audit
AFTER INSERT OR UPDATE OF scan_status ON f05_private_artifacts
FOR EACH ROW EXECUTE FUNCTION f05_record_artifact_scan_audit();

CREATE OR REPLACE FUNCTION f05_guard_package_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE'
       OR OLD.id <> NEW.id
       OR OLD.engagement_month_id <> NEW.engagement_month_id
       OR OLD.handoff_id <> NEW.handoff_id
       OR OLD.invoice_id <> NEW.invoice_id
       OR OLD.invoice_version <> NEW.invoice_version
       OR OLD.invoice_document_artifact_id <> NEW.invoice_document_artifact_id
       OR OLD.invoice_document_hash <> NEW.invoice_document_hash
       OR OLD.canonical_manifest <> NEW.canonical_manifest
       OR OLD.canonical_input_hash <> NEW.canonical_input_hash
       OR OLD.version <> NEW.version
       OR OLD.generated_at <> NEW.generated_at
       OR OLD.generated_by_subject <> NEW.generated_by_subject
       OR OLD.correlation_id <> NEW.correlation_id
       OR NOT (
           OLD.status = NEW.status
           OR (OLD.status = 'GENERATING' AND NEW.status IN ('CURRENT', 'FAILED'))
           OR (OLD.status = 'CURRENT' AND NEW.status IN ('SUPERSEDED', 'INVALIDATED'))
       )
    THEN
        RAISE EXCEPTION 'F05 package header transition is not permitted'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f05_package_header_guard
BEFORE UPDATE OR DELETE ON evidence_package_versions
FOR EACH ROW EXECUTE FUNCTION f05_guard_package_change();

CREATE OR REPLACE FUNCTION f05_guard_readiness_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE'
       OR OLD.id <> NEW.id
       OR OLD.invoice_id <> NEW.invoice_id
       OR OLD.invoice_version <> NEW.invoice_version
       OR OLD.handoff_id <> NEW.handoff_id
       OR OLD.input_manifest <> NEW.input_manifest
       OR OLD.input_hash <> NEW.input_hash
       OR OLD.policy_version <> NEW.policy_version
       OR OLD.overall_status <> NEW.overall_status
       OR OLD.evaluated_at <> NEW.evaluated_at
       OR OLD.current_result = FALSE
       OR (OLD.current_result = TRUE AND NEW.current_result = TRUE
           AND OLD.eligible <> NEW.eligible)
       OR (OLD.current_result = TRUE AND NEW.current_result = FALSE
           AND NEW.eligible)
    THEN
        RAISE EXCEPTION 'F05 readiness result can only be invalidated'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f05_readiness_run_guard
BEFORE UPDATE OR DELETE ON invoice_readiness_runs
FOR EACH ROW EXECUTE FUNCTION f05_guard_readiness_change();

CREATE OR REPLACE FUNCTION f05_guard_invoice_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    allowed BOOLEAN;
BEGIN
    IF TG_OP = 'DELETE'
       OR OLD.id <> NEW.id
       OR OLD.engagement_month_id <> NEW.engagement_month_id
       OR OLD.vendor_organization_id <> NEW.vendor_organization_id
       OR OLD.invoice_type <> NEW.invoice_type
       OR OLD.invoice_number <> NEW.invoice_number
       OR OLD.normalized_invoice_number <> NEW.normalized_invoice_number
       OR OLD.invoice_date <> NEW.invoice_date
       OR OLD.billing_period_start <> NEW.billing_period_start
       OR OLD.billing_period_end <> NEW.billing_period_end
       OR OLD.currency <> NEW.currency
       OR OLD.taxable_value IS DISTINCT FROM NEW.taxable_value
       OR OLD.tax_value IS DISTINCT FROM NEW.tax_value
       OR OLD.total_value IS DISTINCT FROM NEW.total_value
       OR OLD.po_reference IS DISTINCT FROM NEW.po_reference
       OR OLD.work_order_reference IS DISTINCT FROM NEW.work_order_reference
       OR OLD.created_by_subject <> NEW.created_by_subject
       OR OLD.created_at <> NEW.created_at
       OR OLD.correlation_id <> NEW.correlation_id
    THEN
        RAISE EXCEPTION 'F05 represented invoice fields are immutable'
            USING ERRCODE = '23514';
    END IF;

    allowed := OLD.status = NEW.status OR (OLD.status, NEW.status) IN (
        ('DRAFT', 'UPLOADED'),
        ('UPLOADED', 'EVIDENCE_PENDING'),
        ('UPLOADED', 'READY_FOR_VENDOR_SUBMISSION'),
        ('EVIDENCE_PENDING', 'UPLOADED'),
        ('EVIDENCE_PENDING', 'READY_FOR_VENDOR_SUBMISSION'),
        ('EVIDENCE_PENDING', 'EXCEPTION_ACCEPTED'),
        ('READY_FOR_VENDOR_SUBMISSION', 'EVIDENCE_PENDING'),
        ('SUBMITTED_TO_PROCUREMENT', 'EVIDENCE_PENDING'),
        ('PROCUREMENT_REVIEW', 'EVIDENCE_PENDING'),
        ('CHANGES_REQUESTED', 'EVIDENCE_PENDING'),
        ('ON_HOLD', 'EVIDENCE_PENDING'),
        ('REJECTED', 'EVIDENCE_PENDING'),
        ('EXCEPTION_ACCEPTED', 'EVIDENCE_PENDING'),
        ('APPROVED_FOR_PROCESSING', 'EVIDENCE_PENDING'),
        ('PAYMENT_INITIATED', 'EVIDENCE_PENDING'),
        ('READY_FOR_VENDOR_SUBMISSION', 'SUBMITTED_TO_PROCUREMENT'),
        ('SUBMITTED_TO_PROCUREMENT', 'PROCUREMENT_REVIEW'),
        ('SUBMITTED_TO_PROCUREMENT', 'APPROVED_FOR_PROCESSING'),
        ('SUBMITTED_TO_PROCUREMENT', 'CHANGES_REQUESTED'),
        ('SUBMITTED_TO_PROCUREMENT', 'ON_HOLD'),
        ('SUBMITTED_TO_PROCUREMENT', 'REJECTED'),
        ('PROCUREMENT_REVIEW', 'APPROVED_FOR_PROCESSING'),
        ('PROCUREMENT_REVIEW', 'CHANGES_REQUESTED'),
        ('PROCUREMENT_REVIEW', 'ON_HOLD'),
        ('PROCUREMENT_REVIEW', 'REJECTED'),
        ('SUBMITTED_TO_PROCUREMENT', 'EXCEPTION_ACCEPTED'),
        ('PROCUREMENT_REVIEW', 'EXCEPTION_ACCEPTED'),
        ('CHANGES_REQUESTED', 'EXCEPTION_ACCEPTED'),
        ('ON_HOLD', 'EXCEPTION_ACCEPTED'),
        ('EXCEPTION_ACCEPTED', 'APPROVED_FOR_PROCESSING'),
        ('EXCEPTION_ACCEPTED', 'CHANGES_REQUESTED'),
        ('EXCEPTION_ACCEPTED', 'ON_HOLD'),
        ('EXCEPTION_ACCEPTED', 'REJECTED'),
        ('EXCEPTION_ACCEPTED', 'SUBMITTED_TO_PROCUREMENT'),
        ('CHANGES_REQUESTED', 'UPLOADED'),
        ('ON_HOLD', 'PROCUREMENT_REVIEW'),
        ('REJECTED', 'UPLOADED'),
        ('APPROVED_FOR_PROCESSING', 'PAYMENT_INITIATED'),
        ('APPROVED_FOR_PROCESSING', 'ON_HOLD'),
        ('PAYMENT_INITIATED', 'PAID'),
        ('PAYMENT_INITIATED', 'ON_HOLD'),
        ('ON_HOLD', 'PAYMENT_INITIATED'),
        ('PAID', 'CLOSED'),
        ('DRAFT', 'SUPERSEDED'),
        ('UPLOADED', 'SUPERSEDED'),
        ('EVIDENCE_PENDING', 'SUPERSEDED'),
        ('READY_FOR_VENDOR_SUBMISSION', 'SUPERSEDED'),
        ('CHANGES_REQUESTED', 'SUPERSEDED'),
        ('REJECTED', 'SUPERSEDED'),
        ('DRAFT', 'CANCELLED'),
        ('UPLOADED', 'CANCELLED'),
        ('EVIDENCE_PENDING', 'CANCELLED'),
        ('READY_FOR_VENDOR_SUBMISSION', 'CANCELLED')
    );
    IF NOT allowed THEN
        RAISE EXCEPTION 'Illegal F05 invoice state transition % -> %',
            OLD.status, NEW.status USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f05_invoice_transition_guard
BEFORE UPDATE OR DELETE ON invoices
FOR EACH ROW EXECUTE FUNCTION f05_guard_invoice_change();

CREATE OR REPLACE FUNCTION f05_enforce_artifact_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    parent_month UUID;
    artifact_month UUID;
BEGIN
    IF TG_TABLE_NAME = 'invoice_versions' THEN
        IF NEW.document_artifact_id IS NULL THEN RETURN NEW; END IF;
        SELECT invoice.engagement_month_id INTO parent_month
        FROM invoices invoice WHERE invoice.id = NEW.invoice_id;
        SELECT engagement_month_id INTO artifact_month
        FROM f05_private_artifacts WHERE id = NEW.document_artifact_id;
    ELSE
        IF NEW.artifact_id IS NULL THEN RETURN NEW; END IF;
        SELECT package.engagement_month_id INTO parent_month
        FROM evidence_package_versions package
        WHERE package.id = NEW.package_version_id;
        SELECT engagement_month_id INTO artifact_month
        FROM f05_private_artifacts WHERE id = NEW.artifact_id;
    END IF;
    IF parent_month IS NULL OR artifact_month IS NULL
       OR parent_month <> artifact_month THEN
        RAISE EXCEPTION 'F05 artifact belongs to a different engagement month'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f05_invoice_artifact_scope
BEFORE INSERT ON invoice_versions
FOR EACH ROW EXECUTE FUNCTION f05_enforce_artifact_scope();
CREATE TRIGGER f05_item_artifact_scope
BEFORE INSERT ON evidence_package_items
FOR EACH ROW EXECUTE FUNCTION f05_enforce_artifact_scope();
CREATE TRIGGER f05_output_artifact_scope
BEFORE INSERT ON evidence_package_outputs
FOR EACH ROW EXECUTE FUNCTION f05_enforce_artifact_scope();

CREATE OR REPLACE FUNCTION enforce_f05_package_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM effective_f05_certification_handoffs handoff
        WHERE handoff.id = NEW.handoff_id
          AND handoff.engagement_month_id = NEW.engagement_month_id
          AND handoff.effective_status <> 'INVALIDATED'
    ) OR (
        NEW.supersedes_id IS NOT NULL
        AND NOT EXISTS (
            SELECT 1 FROM evidence_package_versions prior
            WHERE prior.id = NEW.supersedes_id
              AND prior.engagement_month_id = NEW.engagement_month_id
        )
    ) OR NOT EXISTS (
        SELECT 1
        FROM invoices invoice
        JOIN invoice_versions version
          ON version.invoice_id = invoice.id
         AND version.version = NEW.invoice_version
        JOIN f05_private_artifacts artifact
          ON artifact.id = version.document_artifact_id
        WHERE invoice.id = NEW.invoice_id
          AND invoice.engagement_month_id = NEW.engagement_month_id
          AND invoice.invoice_type = 'PRIMARY'
          AND invoice.status NOT IN ('SUPERSEDED', 'CANCELLED')
          AND invoice.current_version = NEW.invoice_version
          AND version.document_artifact_id =
              NEW.invoice_document_artifact_id
          AND artifact.content_hash = NEW.invoice_document_hash
          AND artifact.scan_status = 'PASSED'
    ) THEN
        RAISE EXCEPTION
            'Package contains an invalid handoff or primary invoice lineage'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f05_package_scope_gate
BEFORE INSERT ON evidence_package_versions
FOR EACH ROW EXECUTE FUNCTION enforce_f05_package_scope();

CREATE OR REPLACE FUNCTION enforce_f05_readiness_package_lineage()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.package_version_id IS NOT NULL AND NOT EXISTS (
        SELECT 1
        FROM evidence_package_versions package
        WHERE package.id = NEW.package_version_id
          AND package.invoice_id = NEW.invoice_id
          AND package.invoice_version = NEW.invoice_version
          AND package.status = 'CURRENT'
    ) THEN
        RAISE EXCEPTION
            'Readiness package does not represent the exact invoice version'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f05_readiness_package_lineage_gate
BEFORE INSERT ON invoice_readiness_runs
FOR EACH ROW EXECUTE FUNCTION enforce_f05_readiness_package_lineage();

CREATE OR REPLACE FUNCTION enforce_f05_review_package_lineage()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM evidence_package_versions package
        JOIN invoice_readiness_runs readiness
          ON readiness.id = NEW.readiness_run_id
        WHERE package.id = NEW.package_version_id
          AND package.invoice_id = NEW.invoice_id
          AND package.invoice_version = NEW.invoice_version
          AND package.status = 'CURRENT'
          AND readiness.invoice_id = NEW.invoice_id
          AND readiness.invoice_version = NEW.invoice_version
          AND readiness.package_version_id = NEW.package_version_id
          AND readiness.current_result
    ) THEN
        RAISE EXCEPTION
            'Review does not reference the exact current invoice package lineage'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f05_review_package_lineage_gate
BEFORE INSERT ON procurement_reviews
FOR EACH ROW EXECUTE FUNCTION enforce_f05_review_package_lineage();

CREATE OR REPLACE FUNCTION enforce_f05_invoice_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM engagement_months month
        JOIN engagements engagement ON engagement.id = month.engagement_id
        WHERE month.id = NEW.engagement_month_id
          AND engagement.vendor_organization_id = NEW.vendor_organization_id
          AND NEW.billing_period_start = month.month_start_date
          AND NEW.billing_period_end =
              (month.month_start_date + INTERVAL '1 month - 1 day')::date
    ) OR (
        NEW.current_package_version_id IS NOT NULL
        AND NOT EXISTS (
            SELECT 1 FROM evidence_package_versions package
            WHERE package.id = NEW.current_package_version_id
              AND package.engagement_month_id = NEW.engagement_month_id
              AND package.invoice_id = NEW.id
              AND package.invoice_version = NEW.current_version
        )
    ) THEN
        RAISE EXCEPTION 'Invoice is outside vendor, month or package scope'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f05_invoice_scope_gate
BEFORE INSERT OR UPDATE OF engagement_month_id, vendor_organization_id,
    billing_period_start, billing_period_end, current_package_version_id
ON invoices
FOR EACH ROW EXECUTE FUNCTION enforce_f05_invoice_scope();

CREATE OR REPLACE FUNCTION apply_f05_handoff_invalidation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    month_id UUID;
    invalidation_event_id UUID;
BEGIN
    SELECT handoff.engagement_month_id INTO month_id
    FROM f05_certification_handoffs handoff
    WHERE handoff.id = NEW.handoff_id;

    INSERT INTO f05_invalidation_effects (
        id, handoff_invalidation_id, engagement_month_id,
        reason_code, correlation_id
    ) VALUES (
        gen_random_uuid(), NEW.id, month_id, NEW.reason_code, NEW.correlation_id
    ) ON CONFLICT (handoff_invalidation_id) DO NOTHING;

    UPDATE evidence_package_versions
    SET status = 'INVALIDATED',
        invalidation_reason = NEW.reason_code
    WHERE handoff_id = NEW.handoff_id
      AND status = 'CURRENT';

    UPDATE invoice_readiness_runs
    SET current_result = FALSE,
        eligible = FALSE,
        invalidated_at = NEW.invalidated_at
    WHERE handoff_id = NEW.handoff_id
      AND current_result;

    UPDATE invoices invoice
    SET status = 'EVIDENCE_PENDING',
        current_readiness_run_id = NULL,
        optimistic_version = optimistic_version + 1,
        updated_at = NEW.invalidated_at
    WHERE invoice.engagement_month_id = month_id
      AND invoice.status IN (
          'UPLOADED', 'READY_FOR_VENDOR_SUBMISSION',
          'SUBMITTED_TO_PROCUREMENT', 'PROCUREMENT_REVIEW'
      );

    invalidation_event_id := gen_random_uuid();
    INSERT INTO f05_domain_events (
        id, engagement_month_id, event_type, aggregate_type,
        aggregate_id, aggregate_version, payload, actor_subject,
        correlation_id, causation_id
    ) VALUES (
        invalidation_event_id, month_id, 'f05.invalidated.v1', 'F04_HANDOFF',
        NEW.handoff_id, 1,
        jsonb_build_object(
            'handoffInvalidationId', NEW.id,
            'reasonCode', NEW.reason_code
        ),
        NEW.invalidated_by_subject, NEW.correlation_id, NEW.id
    ) ON CONFLICT DO NOTHING;

    INSERT INTO f05_outbox(id, event_id, status, next_attempt_at)
    SELECT gen_random_uuid(), invalidation_event_id, 'PENDING', CURRENT_TIMESTAMP
    WHERE EXISTS (
        SELECT 1 FROM f05_domain_events event
        WHERE event.id = invalidation_event_id
    )
    ON CONFLICT (event_id) DO NOTHING;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f05_consume_handoff_invalidation
AFTER INSERT ON f05_handoff_invalidations
FOR EACH ROW EXECUTE FUNCTION apply_f05_handoff_invalidation();

CREATE OR REPLACE VIEW f05_control_tower
WITH (security_invoker = true)
AS
SELECT month.id AS engagement_month_id,
       month.engagement_id,
       engagement.vendor_organization_id,
       engagement.client_organization_id,
       engagement.procurement_organization_id,
       month.month_start_date,
       package.id AS package_version_id,
       package.version AS package_version,
       package.status AS package_status,
       invoice.id AS invoice_id,
       invoice.invoice_number,
       invoice.status AS invoice_status,
       readiness.overall_status AS readiness_status,
       readiness.evaluated_at AS readiness_evaluated_at,
       payment.status AS payment_status,
       payment.status_at AS payment_status_at,
       EXISTS (
           SELECT 1 FROM f05_handoff_invalidations invalidation
           JOIN f05_certification_handoffs handoff
             ON handoff.id = invalidation.handoff_id
           WHERE handoff.engagement_month_id = month.id
       ) AS reopened_or_invalidated
FROM engagement_months month
JOIN engagements engagement ON engagement.id = month.engagement_id
LEFT JOIN LATERAL (
    SELECT value.*
    FROM evidence_package_versions value
    WHERE value.engagement_month_id = month.id
    ORDER BY value.version DESC
    LIMIT 1
) package ON TRUE
LEFT JOIN LATERAL (
    SELECT value.*
    FROM invoices value
    WHERE value.engagement_month_id = month.id
      AND value.status NOT IN ('SUPERSEDED', 'CANCELLED')
    ORDER BY value.updated_at DESC
    LIMIT 1
) invoice ON TRUE
LEFT JOIN invoice_readiness_runs readiness
  ON readiness.id = invoice.current_readiness_run_id
LEFT JOIN LATERAL (
    SELECT value.status, value.status_at
    FROM payment_status_history value
    WHERE value.invoice_id = invoice.id
    ORDER BY value.sequence_number DESC
    LIMIT 1
) payment ON TRUE;

CREATE INDEX idx_f05_package_month_version
    ON evidence_package_versions(engagement_month_id, version DESC);
CREATE INDEX idx_f05_readiness_invoice_time
    ON invoice_readiness_runs(invoice_id, evaluated_at DESC);
CREATE INDEX idx_f05_review_invoice_time
    ON procurement_reviews(invoice_id, reviewed_at DESC);
CREATE INDEX idx_f05_payment_invoice_sequence
    ON payment_status_history(invoice_id, sequence_number DESC);
CREATE INDEX idx_f05_export_scope_status
    ON f05_report_exports(organization_id, status, requested_at DESC);
CREATE INDEX idx_f05_package_history_keyset
    ON evidence_package_versions(
        engagement_month_id, version DESC, id DESC, generated_at);
CREATE INDEX idx_f05_invoice_list_keyset
    ON invoices(engagement_month_id, created_at DESC, id DESC);
CREATE INDEX idx_f05_package_access_keyset
    ON f05_audit_events(
        object_type, object_id, recorded_at DESC, id DESC);
CREATE INDEX idx_f05_package_share_keyset
    ON evidence_package_shares(
        package_version_id, created_at DESC, id DESC);
CREATE INDEX idx_f05_month_list_keyset
    ON engagement_months(
        engagement_id, month_start_date DESC, id DESC, created_at);
