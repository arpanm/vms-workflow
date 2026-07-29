CREATE TABLE greythr_connections (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    display_name VARCHAR(128) NOT NULL,
    status VARCHAR(24) NOT NULL
        CHECK (status IN ('DISCOVERED', 'ACTIVE', 'DEGRADED', 'DISABLED')),
    adapter_mode VARCHAR(24) NOT NULL
        CHECK (adapter_mode IN ('PROVIDER_NEUTRAL', 'RECORDED_FIXTURE')),
    credential_reference VARCHAR(512),
    capability_certification_id UUID
        REFERENCES integration_capability_certifications(id),
    last_attempt_at TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    last_error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_subject VARCHAR(255) NOT NULL,
    CONSTRAINT uq_greythr_connection_name
        UNIQUE (organization_id, display_name),
    CONSTRAINT ck_greythr_active_certification
        CHECK (status <> 'ACTIVE' OR capability_certification_id IS NOT NULL)
);

CREATE TABLE greythr_recorded_pages (
    connection_id UUID NOT NULL REFERENCES greythr_connections(id),
    page_number INTEGER NOT NULL CHECK (page_number > 0),
    response_mode VARCHAR(24) NOT NULL DEFAULT 'AVAILABLE'
        CHECK (response_mode IN (
            'AVAILABLE', 'TIMEOUT', 'RATE_LIMITED', 'UNAVAILABLE', 'MALFORMED'
        )),
    payload JSONB NOT NULL,
    source_updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (connection_id, page_number)
);

CREATE TABLE greythr_sync_runs (
    id UUID PRIMARY KEY,
    connection_id UUID NOT NULL REFERENCES greythr_connections(id),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash VARCHAR(64) NOT NULL CHECK (length(request_hash) = 64),
    date_from DATE NOT NULL,
    date_to DATE NOT NULL,
    status VARCHAR(24) NOT NULL
        CHECK (status IN ('RUNNING', 'COMPLETED', 'DEGRADED', 'FAILED')),
    employee_count INTEGER NOT NULL DEFAULT 0 CHECK (employee_count >= 0),
    attendance_count INTEGER NOT NULL DEFAULT 0 CHECK (attendance_count >= 0),
    leave_count INTEGER NOT NULL DEFAULT 0 CHECK (leave_count >= 0),
    conflict_count INTEGER NOT NULL DEFAULT 0 CHECK (conflict_count >= 0),
    page_count INTEGER NOT NULL DEFAULT 0 CHECK (page_count >= 0),
    error_code VARCHAR(64),
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    correlation_id UUID NOT NULL,
    CONSTRAINT uq_greythr_sync_idempotency
        UNIQUE (connection_id, idempotency_key),
    CONSTRAINT ck_greythr_sync_dates CHECK (date_to >= date_from)
);

CREATE TABLE greythr_imported_facts (
    id UUID PRIMARY KEY,
    connection_id UUID NOT NULL REFERENCES greythr_connections(id),
    sync_run_id UUID NOT NULL REFERENCES greythr_sync_runs(id),
    employee_id UUID NOT NULL REFERENCES employees(id),
    provider_employee_id VARCHAR(128) NOT NULL,
    fact_kind VARCHAR(24) NOT NULL
        CHECK (fact_kind IN ('EMPLOYEE', 'ATTENDANCE', 'LEAVE')),
    work_date DATE,
    provider_record_id VARCHAR(255) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL CHECK (length(payload_hash) = 64),
    supersedes_id UUID REFERENCES greythr_imported_facts(id),
    source_updated_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    payload JSONB NOT NULL,
    CONSTRAINT uq_greythr_provider_fact
        UNIQUE (connection_id, fact_kind, provider_record_id, payload_hash)
);

CREATE TABLE greythr_reconciliation_items (
    id UUID PRIMARY KEY,
    sync_run_id UUID NOT NULL REFERENCES greythr_sync_runs(id),
    connection_id UUID NOT NULL REFERENCES greythr_connections(id),
    employee_id UUID NOT NULL REFERENCES employees(id),
    work_date DATE NOT NULL,
    conflict_type VARCHAR(40) NOT NULL
        CHECK (conflict_type IN (
            'ATTENDANCE_SOURCE_CONFLICT', 'LEAVE_SOURCE_CONFLICT'
        )),
    provider_fact_id UUID NOT NULL REFERENCES greythr_imported_facts(id),
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'USE_GREYTHR', 'KEEP_INTERNAL')),
    decision_reason TEXT,
    decided_at TIMESTAMPTZ,
    decided_by_subject VARCHAR(255),
    CONSTRAINT uq_greythr_reconciliation_fact UNIQUE (provider_fact_id)
);

CREATE TABLE greythr_cutovers (
    id UUID PRIMARY KEY,
    connection_id UUID NOT NULL REFERENCES greythr_connections(id),
    employee_id UUID NOT NULL REFERENCES employees(id),
    capability_certification_id UUID NOT NULL
        REFERENCES integration_capability_certifications(id),
    source_assignment_id UUID NOT NULL
        REFERENCES attendance_source_mode_assignments(id),
    effective_from DATE NOT NULL,
    reason TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_subject VARCHAR(255) NOT NULL,
    CONSTRAINT uq_greythr_employee_cutover
        UNIQUE (employee_id, effective_from)
);

CREATE INDEX idx_greythr_facts_employee_date
    ON greythr_imported_facts(employee_id, work_date, fact_kind);
CREATE INDEX idx_greythr_reconciliation_pending
    ON greythr_reconciliation_items(connection_id, status, work_date);
CREATE INDEX idx_greythr_sync_freshness
    ON greythr_sync_runs(connection_id, completed_at DESC);

CREATE TRIGGER greythr_sync_runs_immutable
BEFORE UPDATE OR DELETE ON greythr_sync_runs
FOR EACH ROW
WHEN (OLD.status <> 'RUNNING')
EXECUTE FUNCTION reject_immutable_change();

CREATE TRIGGER greythr_imported_facts_immutable
BEFORE UPDATE OR DELETE ON greythr_imported_facts
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
