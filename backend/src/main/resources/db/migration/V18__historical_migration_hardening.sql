-- F06 review remediations are additive. V17 remains an immutable migration.

CREATE TABLE migration_validation_attempts (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES migration_jobs(id),
    attempt_number INTEGER NOT NULL CHECK (attempt_number > 0),
    state VARCHAR(16) NOT NULL CHECK (
        state IN ('RUNNING', 'COMPLETED', 'FAILED')),
    source_sha256 VARCHAR(64) NOT NULL CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    result_hash VARCHAR(64),
    started_by_subject VARCHAR(255) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    UNIQUE (job_id, attempt_number),
    CHECK (
        (state = 'RUNNING' AND completed_at IS NULL)
        OR (state <> 'RUNNING' AND completed_at IS NOT NULL))
);

ALTER TABLE migration_rows
    ADD COLUMN validation_attempt_id UUID
        REFERENCES migration_validation_attempts(id);
ALTER TABLE migration_row_findings
    ADD COLUMN validation_attempt_id UUID
        REFERENCES migration_validation_attempts(id);

ALTER TABLE migration_approvals
    ADD COLUMN reconciliation_id UUID
        REFERENCES migration_reconciliation_reports(id),
    ADD COLUMN reconciliation_hash VARCHAR(64),
    ADD COLUMN authority_role_code VARCHAR(64),
    ADD COLUMN authority_organization_id UUID REFERENCES organizations(id);
ALTER TABLE migration_approvals
    ADD CONSTRAINT ck_migration_approval_reconciliation
    CHECK (
        (decision = 'REJECTED')
        OR (reconciliation_id IS NOT NULL
            AND reconciliation_hash ~ '^[0-9a-f]{64}$'));

CREATE TABLE migration_domain_provenance (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES migration_jobs(id),
    row_id UUID NOT NULL REFERENCES migration_rows(id),
    template_code VARCHAR(64) NOT NULL,
    domain_table VARCHAR(100) NOT NULL,
    domain_record_id UUID NOT NULL,
    domain_version INTEGER NOT NULL DEFAULT 1 CHECK (domain_version > 0),
    source_file_id UUID NOT NULL REFERENCES migration_source_files(id),
    source_sha256 VARCHAR(64) NOT NULL CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    represented_at TIMESTAMPTZ,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    compensation_record_id UUID,
    UNIQUE (job_id, row_id, domain_table, domain_record_id)
);
CREATE INDEX idx_migration_domain_provenance_record
    ON migration_domain_provenance(domain_table, domain_record_id, active);

CREATE TABLE migration_scan_verdicts (
    id UUID PRIMARY KEY,
    source_file_id UUID NOT NULL REFERENCES migration_source_files(id),
    verdict VARCHAR(16) NOT NULL CHECK (
        verdict IN ('PASSED', 'FAILED', 'QUARANTINED')),
    scanner_name VARCHAR(100) NOT NULL,
    scanner_version VARCHAR(100),
    signature_version VARCHAR(100),
    reason_code VARCHAR(100),
    content_sha256 VARCHAR(64) NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    decided_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (source_file_id)
);

CREATE TABLE migration_security_events (
    id UUID PRIMARY KEY,
    engagement_id UUID,
    organization_id UUID,
    job_id UUID,
    event_type VARCHAR(100) NOT NULL,
    actor_subject VARCHAR(255),
    outcome VARCHAR(24) NOT NULL,
    correlation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE OR REPLACE FUNCTION f06_apply_scan_verdict()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    UPDATE migration_source_files
    SET scan_status = NEW.verdict,
        scan_reason_code = NEW.reason_code
    WHERE id = NEW.source_file_id;
    RETURN NEW;
END;
$$;

-- Replace V17's blanket source immutability with the only permitted mutation:
-- PENDING -> scanner verdict. Metadata, bytes and passed verdicts remain fixed.
DROP TRIGGER migration_source_files_immutable ON migration_source_files;
CREATE OR REPLACE FUNCTION f06_source_scan_only()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'migration source evidence cannot be deleted';
    END IF;
    IF OLD.scan_status <> 'PENDING'
       OR NEW.scan_status NOT IN ('PASSED', 'FAILED', 'QUARANTINED')
       OR (to_jsonb(NEW) - ARRAY['scan_status','scan_reason_code']::text[])
          <> (to_jsonb(OLD) - ARRAY['scan_status','scan_reason_code']::text[]) THEN
        RAISE EXCEPTION 'only a pending source scanner verdict may mutate';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER migration_source_files_scan_only
BEFORE UPDATE OR DELETE ON migration_source_files
FOR EACH ROW EXECUTE FUNCTION f06_source_scan_only();
CREATE TRIGGER migration_scan_verdict_apply
AFTER INSERT ON migration_scan_verdicts
FOR EACH ROW EXECUTE FUNCTION f06_apply_scan_verdict();

CREATE TRIGGER migration_validation_attempts_immutable
BEFORE DELETE ON migration_validation_attempts
FOR EACH ROW EXECUTE FUNCTION f06_reject_mutation();
CREATE TRIGGER migration_domain_provenance_no_delete
BEFORE DELETE ON migration_domain_provenance
FOR EACH ROW EXECUTE FUNCTION f06_reject_mutation();
CREATE TRIGGER migration_scan_verdicts_immutable
BEFORE UPDATE OR DELETE ON migration_scan_verdicts
FOR EACH ROW EXECUTE FUNCTION f06_reject_mutation();
CREATE TRIGGER migration_security_events_immutable
BEFORE UPDATE OR DELETE ON migration_security_events
FOR EACH ROW EXECUTE FUNCTION f06_reject_mutation();
