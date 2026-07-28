-- F06 governed historical migration. Source bytes and staging facts are kept
-- separate from canonical effects; represented and recorded time never alias.

INSERT INTO permissions (id, code, description) VALUES
    ('10000000-0000-0000-0000-000000000070', 'migration.read', 'Read scoped migration catalog, jobs and reconciliation'),
    ('10000000-0000-0000-0000-000000000071', 'migration.upload', 'Upload scoped immutable migration source files'),
    ('10000000-0000-0000-0000-000000000072', 'migration.validate', 'Validate and resolve scoped migration staging rows'),
    ('10000000-0000-0000-0000-000000000073', 'migration.approve', 'Approve exact migration job versions'),
    ('10000000-0000-0000-0000-000000000074', 'migration.commit', 'Commit approved migration jobs'),
    ('10000000-0000-0000-0000-000000000075', 'migration.rollback', 'Compensate eligible committed migration batches'),
    ('10000000-0000-0000-0000-000000000076', 'migration.retro', 'Create and decide historical retro requests'),
    ('10000000-0000-0000-0000-000000000077', 'migration.audit.read', 'Download restricted migration source and error evidence');

INSERT INTO roles (id, code, name, description) VALUES
    ('11000000-0000-0000-0000-000000000012', 'MIGRATION_LEAD', 'Migration lead', 'Scoped migration upload, validation and commit authority')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT role.id, permission.id
FROM roles role
CROSS JOIN permissions permission
WHERE
    (role.code IN ('ORG_ADMIN', 'ENGAGEMENT_ADMIN', 'MIGRATION_LEAD')
      AND permission.code IN (
        'migration.read', 'migration.upload', 'migration.validate',
        'migration.approve', 'migration.commit', 'migration.rollback',
        'migration.retro', 'migration.audit.read'))
    OR (role.code = 'GOVERNANCE_REVIEWER'
      AND permission.code IN (
        'migration.read', 'migration.approve', 'migration.retro',
        'migration.audit.read'))
    OR (role.code = 'CLIENT_PRODUCT_OWNER'
      AND permission.code IN (
        'migration.read', 'migration.approve', 'migration.retro'))
    OR (role.code = 'AUDITOR_READONLY'
      AND permission.code IN ('migration.read', 'migration.audit.read'))
ON CONFLICT DO NOTHING;

ALTER TABLE engagement_months DROP CONSTRAINT engagement_months_state_check;
ALTER TABLE engagement_months ADD CONSTRAINT engagement_months_state_check
    CHECK (state IN (
        'HISTORICAL_DRAFT', 'HISTORICAL_IMPORT_IN_PROGRESS',
        'HISTORICAL_REVIEW', 'HISTORICAL_PENDING_CERTIFICATION',
        'HISTORICAL_PENDING_CONFIRMATION',
        'DRAFT', 'PLANNING', 'PLAN_PENDING_APPROVAL', 'PLAN_APPROVED', 'ACTIVE',
        'DELIVERY_SUBMITTED', 'DELIVERY_REVIEW', 'CONFIRMATION_PENDING',
        'CONFIRMED', 'INVOICE_READY', 'INVOICE_SUBMITTED', 'CLOSED',
        'REOPEN_REQUESTED', 'REOPENED'));

CREATE TABLE migration_source_files (
    id UUID PRIMARY KEY,
    engagement_id UUID NOT NULL REFERENCES engagements(id),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    template_code VARCHAR(64) NOT NULL,
    template_version VARCHAR(16) NOT NULL,
    safe_filename VARCHAR(255) NOT NULL,
    media_type VARCHAR(160) NOT NULL,
    byte_size BIGINT NOT NULL CHECK (byte_size > 0 AND byte_size <= 26214400),
    sha256 VARCHAR(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    scan_status VARCHAR(20) NOT NULL CHECK (
        scan_status IN ('PENDING', 'PASSED', 'FAILED', 'QUARANTINED')),
    scan_reason_code VARCHAR(100),
    uploaded_by_subject VARCHAR(255) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    retention_until DATE NOT NULL,
    UNIQUE (engagement_id, organization_id, template_code, template_version, sha256),
    UNIQUE (id, engagement_id, organization_id)
);

CREATE TABLE migration_source_blobs (
    source_file_id UUID PRIMARY KEY REFERENCES migration_source_files(id),
    content BYTEA NOT NULL,
    stored_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE migration_jobs (
    id UUID PRIMARY KEY,
    source_file_id UUID NOT NULL,
    engagement_id UUID NOT NULL REFERENCES engagements(id),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    engagement_month_id UUID REFERENCES engagement_months(id),
    template_code VARCHAR(64) NOT NULL,
    template_version VARCHAR(16) NOT NULL,
    mode VARCHAR(24) NOT NULL CHECK (
        mode IN ('DRY_RUN', 'COMMIT', 'REPROCESS_REJECTS', 'SUPERSEDE')),
    state VARCHAR(32) NOT NULL CHECK (state IN (
        'UPLOADED', 'SCANNING', 'PARSING', 'VALIDATING',
        'READY_TO_COMMIT', 'COMMITTING', 'COMPLETED',
        'COMPLETED_WITH_ERRORS', 'FAILED', 'CANCELLED', 'ROLLED_BACK')),
    partial_commit BOOLEAN NOT NULL DEFAULT FALSE,
    parent_job_id UUID REFERENCES migration_jobs(id),
    prior_job_id UUID REFERENCES migration_jobs(id),
    requested_by_subject VARCHAR(255) NOT NULL,
    row_count INTEGER NOT NULL DEFAULT 0 CHECK (row_count >= 0),
    valid_count INTEGER NOT NULL DEFAULT 0 CHECK (valid_count >= 0),
    warning_count INTEGER NOT NULL DEFAULT 0 CHECK (warning_count >= 0),
    invalid_count INTEGER NOT NULL DEFAULT 0 CHECK (invalid_count >= 0),
    committed_count INTEGER NOT NULL DEFAULT 0 CHECK (committed_count >= 0),
    rejected_count INTEGER NOT NULL DEFAULT 0 CHECK (rejected_count >= 0),
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    lease_owner VARCHAR(255),
    lease_until TIMESTAMPTZ,
    retry_count INTEGER NOT NULL DEFAULT 0 CHECK (retry_count BETWEEN 0 AND 10),
    dead_lettered_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (source_file_id, engagement_id, organization_id)
        REFERENCES migration_source_files(id, engagement_id, organization_id)
);
CREATE UNIQUE INDEX uq_migration_job_source_mode_parent
    ON migration_jobs(
        source_file_id, mode,
        COALESCE(parent_job_id, '00000000-0000-0000-0000-000000000000'::uuid));
CREATE INDEX idx_migration_jobs_scope
    ON migration_jobs(engagement_id, organization_id, created_at DESC, id);

CREATE TABLE migration_rows (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES migration_jobs(id),
    row_number INTEGER NOT NULL CHECK (row_number >= 2),
    raw_sha256 VARCHAR(64) NOT NULL CHECK (raw_sha256 ~ '^[0-9a-f]{64}$'),
    natural_key_hash VARCHAR(64) NOT NULL CHECK (natural_key_hash ~ '^[0-9a-f]{64}$'),
    content_hash VARCHAR(64) NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    state VARCHAR(32) NOT NULL CHECK (state IN (
        'VALID', 'WARNING', 'INVALID', 'DUPLICATE_IDENTICAL',
        'DUPLICATE_CONFLICT', 'COMMITTED', 'REJECTED', 'SUPERSEDED')),
    source_type VARCHAR(32) NOT NULL CHECK (source_type IN (
        'GREYTHR_EXPORT', 'LINEAR_API', 'LINEAR_EXPORT', 'ORIGINAL_EMAIL',
        'SIGNED_DOCUMENT', 'APPROVED_SPREADSHEET', 'MANUAL_RECONSTRUCTION',
        'OTHER')),
    confidence VARCHAR(16) NOT NULL CHECK (
        confidence IN ('HIGH', 'MEDIUM', 'LOW', 'UNVERIFIED')),
    represented_at TIMESTAMPTZ,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    normalized_payload JSONB NOT NULL,
    limitations TEXT,
    committed_at TIMESTAMPTZ,
    UNIQUE (job_id, row_number),
    UNIQUE (id, job_id)
);
CREATE INDEX idx_migration_rows_page ON migration_rows(job_id, row_number);
CREATE INDEX idx_migration_rows_natural_key
    ON migration_rows(natural_key_hash, content_hash);

CREATE TABLE migration_row_findings (
    id UUID PRIMARY KEY,
    row_id UUID NOT NULL REFERENCES migration_rows(id),
    job_id UUID NOT NULL REFERENCES migration_jobs(id),
    severity VARCHAR(12) NOT NULL CHECK (severity IN ('WARNING', 'ERROR')),
    code VARCHAR(100) NOT NULL,
    field_name VARCHAR(128),
    safe_message VARCHAR(500) NOT NULL,
    dependency_template VARCHAR(64),
    dependency_key_hash VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (row_id, job_id) REFERENCES migration_rows(id, job_id)
);
CREATE INDEX idx_migration_findings_page
    ON migration_row_findings(job_id, severity, code, id);

CREATE TABLE migration_dependencies (
    job_id UUID NOT NULL REFERENCES migration_jobs(id),
    predecessor_template_code VARCHAR(64) NOT NULL,
    natural_key_hash VARCHAR(64),
    resolution_state VARCHAR(16) NOT NULL CHECK (
        resolution_state IN ('REQUIRED', 'RESOLVED', 'WAIVED')),
    resolved_by_job_id UUID REFERENCES migration_jobs(id)
);
CREATE UNIQUE INDEX uq_migration_dependency
    ON migration_dependencies(
        job_id, predecessor_template_code, COALESCE(natural_key_hash, ''));

CREATE TABLE migration_decisions (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES migration_jobs(id),
    row_id UUID REFERENCES migration_rows(id),
    decision VARCHAR(32) NOT NULL CHECK (decision IN (
        'KEEP_EXISTING', 'REJECT', 'VERSIONED_SUPERSEDE',
        'REPLAY', 'CANCEL', 'ROLLBACK', 'REOPEN_CORRECTION')),
    reason VARCHAR(1000) NOT NULL,
    actor_subject VARCHAR(255) NOT NULL,
    job_version BIGINT NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (actor_subject, idempotency_key)
);

CREATE TABLE migration_approvals (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES migration_jobs(id),
    approval_role VARCHAR(24) NOT NULL CHECK (
        approval_role IN ('MIGRATION_LEAD', 'GOVERNANCE', 'BUSINESS')),
    decision VARCHAR(16) NOT NULL CHECK (decision IN ('APPROVED', 'REJECTED')),
    actor_subject VARCHAR(255) NOT NULL,
    job_version BIGINT NOT NULL,
    reason VARCHAR(1000),
    idempotency_key VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (job_id, approval_role, actor_subject, job_version),
    UNIQUE (actor_subject, idempotency_key)
);

CREATE TABLE migration_checkpoints (
    job_id UUID NOT NULL REFERENCES migration_jobs(id),
    phase VARCHAR(32) NOT NULL,
    last_row_number INTEGER NOT NULL DEFAULT 1,
    checkpoint_hash VARCHAR(64) NOT NULL CHECK (checkpoint_hash ~ '^[0-9a-f]{64}$'),
    attempt INTEGER NOT NULL DEFAULT 1,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (job_id, phase)
);

CREATE TABLE migration_canonical_facts (
    id UUID PRIMARY KEY,
    engagement_id UUID NOT NULL REFERENCES engagements(id),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    engagement_month_id UUID REFERENCES engagement_months(id),
    template_code VARCHAR(64) NOT NULL,
    natural_key_hash VARCHAR(64) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    business_payload JSONB NOT NULL,
    represented_at TIMESTAMPTZ,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    source_type VARCHAR(32) NOT NULL,
    confidence VARCHAR(16) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    supersedes_id UUID REFERENCES migration_canonical_facts(id),
    rollback_action_id UUID,
    version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0),
    UNIQUE (engagement_id, template_code, natural_key_hash, version)
);
CREATE UNIQUE INDEX uq_migration_active_fact
    ON migration_canonical_facts(engagement_id, template_code, natural_key_hash)
    WHERE active;

CREATE TABLE migration_attendance_authorities (
    engagement_id UUID NOT NULL REFERENCES engagements(id),
    employee_key_hash VARCHAR(64) NOT NULL CHECK (
        employee_key_hash ~ '^[0-9a-f]{64}$'),
    attendance_date DATE NOT NULL,
    authoritative_template_code VARCHAR(64) NOT NULL CHECK (
        authoritative_template_code IN (
            '07a_attendance_punches', '07b_attendance_daily')),
    job_id UUID NOT NULL REFERENCES migration_jobs(id),
    selected_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (engagement_id, employee_key_hash, attendance_date)
);

CREATE TABLE migration_provenance_links (
    fact_id UUID PRIMARY KEY REFERENCES migration_canonical_facts(id),
    job_id UUID NOT NULL REFERENCES migration_jobs(id),
    source_file_id UUID NOT NULL REFERENCES migration_source_files(id),
    row_id UUID NOT NULL REFERENCES migration_rows(id),
    source_sha256 VARCHAR(64) NOT NULL CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    represented_at TIMESTAMPTZ,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    limitations TEXT,
    UNIQUE (job_id, row_id)
);

CREATE TABLE migration_reconciliation_reports (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES migration_jobs(id),
    engagement_month_id UUID REFERENCES engagement_months(id),
    version INTEGER NOT NULL CHECK (version > 0),
    report_hash VARCHAR(64) NOT NULL CHECK (report_hash ~ '^[0-9a-f]{64}$'),
    source_hashes JSONB NOT NULL,
    counts JSONB NOT NULL,
    coverage JSONB NOT NULL,
    exceptions JSONB NOT NULL,
    canonical_checksum VARCHAR(64) NOT NULL CHECK (canonical_checksum ~ '^[0-9a-f]{64}$'),
    created_by_subject VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (job_id, version),
    UNIQUE (id, report_hash)
);

CREATE TABLE migration_reconciliation_items (
    id UUID PRIMARY KEY,
    report_id UUID NOT NULL REFERENCES migration_reconciliation_reports(id),
    item_type VARCHAR(64) NOT NULL,
    item_key_hash VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    details JSONB NOT NULL,
    UNIQUE (report_id, item_type, item_key_hash)
);

CREATE TABLE migration_reconciliation_signoffs (
    id UUID PRIMARY KEY,
    report_id UUID NOT NULL,
    report_hash VARCHAR(64) NOT NULL,
    signoff_role VARCHAR(24) NOT NULL CHECK (
        signoff_role IN ('MIGRATION_LEAD', 'GOVERNANCE', 'BUSINESS')),
    actor_subject VARCHAR(255) NOT NULL,
    decision VARCHAR(16) NOT NULL CHECK (decision IN ('APPROVED', 'REJECTED')),
    reason VARCHAR(1000),
    idempotency_key VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (report_id, report_hash)
        REFERENCES migration_reconciliation_reports(id, report_hash),
    UNIQUE (report_id, signoff_role, actor_subject),
    UNIQUE (actor_subject, idempotency_key)
);

CREATE TABLE migration_rollback_actions (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES migration_jobs(id),
    action VARCHAR(32) NOT NULL CHECK (
        action IN ('COMPENSATE', 'DENIED_REOPEN_REQUIRED')),
    reason VARCHAR(1000) NOT NULL,
    actor_subject VARCHAR(255) NOT NULL,
    job_version BIGINT NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (actor_subject, idempotency_key)
);
ALTER TABLE migration_canonical_facts ADD CONSTRAINT fk_migration_fact_rollback
    FOREIGN KEY (rollback_action_id) REFERENCES migration_rollback_actions(id);

CREATE TABLE migration_retro_requests (
    id UUID PRIMARY KEY,
    engagement_id UUID NOT NULL REFERENCES engagements(id),
    engagement_month_id UUID NOT NULL REFERENCES engagement_months(id),
    request_type VARCHAR(24) NOT NULL CHECK (
        request_type IN ('COMMITMENT', 'CERTIFICATION', 'CONFIRMATION')),
    state VARCHAR(24) NOT NULL CHECK (
        state IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    represented_month DATE NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    original_actor_unavailable BOOLEAN NOT NULL DEFAULT FALSE,
    delegation_evidence_reference VARCHAR(500),
    procurement_notification_state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    requested_by_subject VARCHAR(255) NOT NULL,
    decided_by_subject VARCHAR(255),
    decision_at TIMESTAMPTZ,
    decision_reason VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 1,
    idempotency_key VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (requested_by_subject, idempotency_key),
    CHECK (represented_month = date_trunc('month', represented_month)::date),
    CHECK (represented_month >= DATE '2026-06-01'),
    CHECK (NOT original_actor_unavailable OR delegation_evidence_reference IS NOT NULL)
);

CREATE TABLE migration_audit_events (
    id UUID PRIMARY KEY,
    engagement_id UUID NOT NULL REFERENCES engagements(id),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    job_id UUID REFERENCES migration_jobs(id),
    event_type VARCHAR(100) NOT NULL,
    actor_subject VARCHAR(255) NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    correlation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE migration_outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    event_key VARCHAR(180) NOT NULL UNIQUE,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ
);

CREATE OR REPLACE FUNCTION f06_reject_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'F06 immutable evidence cannot be updated or deleted';
END;
$$;

CREATE TRIGGER migration_source_files_immutable
BEFORE UPDATE OR DELETE ON migration_source_files
FOR EACH ROW EXECUTE FUNCTION f06_reject_mutation();
CREATE TRIGGER migration_source_blobs_immutable
BEFORE UPDATE OR DELETE ON migration_source_blobs
FOR EACH ROW EXECUTE FUNCTION f06_reject_mutation();
CREATE TRIGGER migration_rows_no_delete
BEFORE DELETE ON migration_rows
FOR EACH ROW EXECUTE FUNCTION f06_reject_mutation();
CREATE TRIGGER migration_findings_immutable
BEFORE UPDATE OR DELETE ON migration_row_findings
FOR EACH ROW EXECUTE FUNCTION f06_reject_mutation();
CREATE TRIGGER migration_decisions_immutable
BEFORE UPDATE OR DELETE ON migration_decisions
FOR EACH ROW EXECUTE FUNCTION f06_reject_mutation();
CREATE TRIGGER migration_approvals_immutable
BEFORE UPDATE OR DELETE ON migration_approvals
FOR EACH ROW EXECUTE FUNCTION f06_reject_mutation();
CREATE TRIGGER migration_provenance_immutable
BEFORE UPDATE OR DELETE ON migration_provenance_links
FOR EACH ROW EXECUTE FUNCTION f06_reject_mutation();
CREATE TRIGGER migration_reconciliation_immutable
BEFORE UPDATE OR DELETE ON migration_reconciliation_reports
FOR EACH ROW EXECUTE FUNCTION f06_reject_mutation();
CREATE TRIGGER migration_signoffs_immutable
BEFORE UPDATE OR DELETE ON migration_reconciliation_signoffs
FOR EACH ROW EXECUTE FUNCTION f06_reject_mutation();
CREATE TRIGGER migration_audit_immutable
BEFORE UPDATE OR DELETE ON migration_audit_events
FOR EACH ROW EXECUTE FUNCTION f06_reject_mutation();

CREATE OR REPLACE FUNCTION f06_enforce_job_transition()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'migration job version must increment once';
    END IF;
    IF OLD.state <> NEW.state AND NOT (
        (OLD.state = 'UPLOADED' AND NEW.state IN ('SCANNING', 'CANCELLED')) OR
        (OLD.state = 'SCANNING' AND NEW.state IN ('PARSING', 'FAILED', 'CANCELLED')) OR
        (OLD.state = 'PARSING' AND NEW.state IN ('VALIDATING', 'FAILED', 'CANCELLED')) OR
        (OLD.state = 'VALIDATING' AND NEW.state IN ('READY_TO_COMMIT', 'FAILED', 'CANCELLED')) OR
        (OLD.state = 'READY_TO_COMMIT' AND NEW.state IN ('COMMITTING', 'CANCELLED', 'FAILED')) OR
        (OLD.state = 'COMMITTING' AND NEW.state IN ('COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED')) OR
        (OLD.state IN ('FAILED', 'COMPLETED_WITH_ERRORS') AND NEW.state IN ('SCANNING', 'VALIDATING')) OR
        (OLD.state IN ('COMPLETED', 'COMPLETED_WITH_ERRORS') AND NEW.state = 'ROLLED_BACK')
    ) THEN
        RAISE EXCEPTION 'illegal migration job transition % -> %', OLD.state, NEW.state;
    END IF;
    NEW.updated_at := CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;
CREATE TRIGGER migration_job_transition
BEFORE UPDATE ON migration_jobs
FOR EACH ROW EXECUTE FUNCTION f06_enforce_job_transition();

CREATE OR REPLACE FUNCTION f06_enforce_signoff_separation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM migration_reconciliation_signoffs existing
        WHERE existing.report_id = NEW.report_id
          AND existing.actor_subject = NEW.actor_subject
          AND existing.decision = 'APPROVED'
    ) THEN
        RAISE EXCEPTION 'reconciliation sign-off actors must be distinct';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER migration_signoff_separation
BEFORE INSERT ON migration_reconciliation_signoffs
FOR EACH ROW EXECUTE FUNCTION f06_enforce_signoff_separation();
