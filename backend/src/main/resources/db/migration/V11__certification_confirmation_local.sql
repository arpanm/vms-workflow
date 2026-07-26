-- F04 provider-neutral certification and confirmation vertical.
-- Provider delivery, mailbox ingestion, object bytes, SSO/step-up and F05 package
-- processing remain behind server-side contracts. This migration stores only local
-- business facts, immutable evidence metadata, hashes and durable work requests.

ALTER TABLE engagement_months
    ADD COLUMN certification_version BIGINT NOT NULL DEFAULT 0
        CHECK (certification_version >= 0);

CREATE TABLE certification_policy_versions (
    id UUID PRIMARY KEY,
    engagement_id UUID NOT NULL REFERENCES engagements(id),
    version INTEGER NOT NULL CHECK (version > 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'SUPERSEDED')),
    attendance_required BOOLEAN NOT NULL,
    separation_of_duties_required BOOLEAN NOT NULL DEFAULT TRUE,
    monthly_decision_required BOOLEAN NOT NULL DEFAULT TRUE,
    manual_second_review_required BOOLEAN NOT NULL DEFAULT TRUE,
    deemed_submission_approval_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    deemed_certification_approval_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    deemed_confirmation_approval_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    quorum_mode VARCHAR(24) NOT NULL CHECK (
        quorum_mode IN ('ANY_ONE', 'ALL', 'N_OF_M', 'ORDERED', 'PROJECT_SPECIFIC')
    ),
    quorum_required INTEGER NOT NULL CHECK (quorum_required > 0),
    token_ttl_seconds INTEGER NOT NULL CHECK (token_ttl_seconds BETWEEN 300 AND 2592000),
    confirmation_due_seconds INTEGER NOT NULL CHECK (
        confirmation_due_seconds BETWEEN 3600 AND 7776000
    ),
    reminder_policy JSONB NOT NULL DEFAULT '{}'::jsonb,
    evidence_policy JSONB NOT NULL DEFAULT '{}'::jsonb,
    recipient_policy JSONB NOT NULL DEFAULT '{}'::jsonb,
    retention_policy JSONB NOT NULL DEFAULT '{}'::jsonb,
    policy_hash VARCHAR(64) NOT NULL CHECK (policy_hash ~ '^[0-9a-f]{64}$'),
    hash_algorithm VARCHAR(16) NOT NULL DEFAULT 'SHA-256'
        CHECK (hash_algorithm = 'SHA-256'),
    hash_schema_version INTEGER NOT NULL DEFAULT 1 CHECK (hash_schema_version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_subject VARCHAR(255) NOT NULL,
    UNIQUE (engagement_id, version)
);
CREATE UNIQUE INDEX uq_current_certification_policy
    ON certification_policy_versions(engagement_id)
    WHERE status = 'ACTIVE';

CREATE TABLE evidence_artifacts (
    id UUID PRIMARY KEY,
    engagement_id UUID NOT NULL REFERENCES engagements(id),
    engagement_month_id UUID REFERENCES engagement_months(id),
    artifact_kind VARCHAR(24) NOT NULL CHECK (
        artifact_kind IN ('OBJECT', 'URL_REFERENCE', 'PROVIDER_REFERENCE')
    ),
    object_key VARCHAR(768),
    object_version VARCHAR(255),
    reference_url VARCHAR(2048),
    original_name VARCHAR(512),
    safe_name VARCHAR(255) NOT NULL,
    declared_mime_type VARCHAR(255),
    sniffed_mime_type VARCHAR(255),
    size_bytes BIGINT CHECK (size_bytes IS NULL OR size_bytes >= 0),
    sha256 VARCHAR(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    classification VARCHAR(24) NOT NULL CHECK (
        classification IN ('PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'RESTRICTED')
    ),
    scan_status VARCHAR(24) NOT NULL CHECK (
        scan_status IN ('NOT_REQUIRED', 'PENDING', 'PASSED', 'FAILED', 'UNKNOWN')
    ),
    retention_status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE'
        CHECK (retention_status IN ('ACTIVE', 'RETAINED', 'EXPIRED', 'DISPOSED')),
    legal_hold BOOLEAN NOT NULL DEFAULT FALSE,
    source VARCHAR(32) NOT NULL CHECK (
        source IN ('VENDOR', 'CLIENT', 'INTEGRATION', 'MANUAL_EVIDENCE', 'MIGRATION')
    ),
    uploader_subject VARCHAR(255),
    provider_status VARCHAR(24) NOT NULL DEFAULT 'NOT_CONFIGURED'
        CHECK (provider_status IN ('NOT_CONFIGURED', 'ACTION_REQUIRED', 'AVAILABLE')),
    represented_at TIMESTAMPTZ,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_artifact_locator CHECK (
        (artifact_kind = 'URL_REFERENCE' AND reference_url IS NOT NULL
            AND object_key IS NULL)
        OR (artifact_kind = 'OBJECT' AND object_key IS NOT NULL
            AND reference_url IS NULL)
        OR (artifact_kind = 'PROVIDER_REFERENCE' AND object_key IS NOT NULL)
    )
);
CREATE UNIQUE INDEX uq_evidence_artifact_object_version
    ON evidence_artifacts(engagement_id, object_key, object_version)
    WHERE object_key IS NOT NULL;

CREATE TABLE delivery_submissions (
    id UUID PRIMARY KEY,
    engagement_month_id UUID NOT NULL REFERENCES engagement_months(id),
    plan_version_id UUID NOT NULL REFERENCES delivery_plan_versions(id),
    baseline_id UUID NOT NULL REFERENCES delivery_plan_baselines(id),
    policy_version_id UUID NOT NULL REFERENCES certification_policy_versions(id),
    version INTEGER NOT NULL CHECK (version > 0),
    status VARCHAR(24) NOT NULL CHECK (
        status IN ('DRAFT', 'SUBMITTED', 'UNDER_REVIEW', 'WITHDRAWN', 'SUPERSEDED')
    ),
    supersedes_id UUID REFERENCES delivery_submissions(id),
    summary TEXT NOT NULL,
    vendor_declaration_accepted BOOLEAN NOT NULL,
    declaration_text TEXT NOT NULL,
    represented_at TIMESTAMPTZ,
    checksum VARCHAR(64),
    hash_algorithm VARCHAR(16) NOT NULL DEFAULT 'SHA-256'
        CHECK (hash_algorithm = 'SHA-256'),
    hash_schema_version INTEGER NOT NULL DEFAULT 1 CHECK (hash_schema_version > 0),
    optimistic_version BIGINT NOT NULL DEFAULT 0 CHECK (optimistic_version >= 0),
    submitted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_subject VARCHAR(255) NOT NULL,
    UNIQUE (engagement_month_id, version),
    UNIQUE (supersedes_id),
    CONSTRAINT ck_delivery_submission_checksum CHECK (
        (status IN ('DRAFT', 'WITHDRAWN', 'SUPERSEDED')
            AND checksum IS NULL AND submitted_at IS NULL)
        OR (status IN ('SUBMITTED', 'UNDER_REVIEW')
            AND checksum ~ '^[0-9a-f]{64}$'
            AND submitted_at IS NOT NULL)
    )
);
CREATE UNIQUE INDEX uq_current_delivery_submission
    ON delivery_submissions(engagement_month_id)
    WHERE status IN ('DRAFT', 'SUBMITTED', 'UNDER_REVIEW');

CREATE TABLE deliverable_delivery_outcomes (
    id UUID PRIMARY KEY,
    submission_id UUID NOT NULL REFERENCES delivery_submissions(id),
    deliverable_version_id UUID NOT NULL REFERENCES delivery_deliverable_versions(id),
    declared_outcome VARCHAR(40) NOT NULL CHECK (declared_outcome IN (
        'COMPLETED', 'COMPLETED_WITH_VARIANCE', 'PARTIALLY_COMPLETED',
        'NOT_COMPLETED', 'DEFERRED_BY_CLIENT', 'DEFERRED_BY_VENDOR',
        'CANCELLED_BY_APPROVED_CHANGE', 'NOT_APPLICABLE'
    )),
    completion_percent INTEGER NOT NULL CHECK (completion_percent BETWEEN 0 AND 100),
    completion_date DATE,
    delivery_summary TEXT NOT NULL,
    limitations TEXT,
    variance_description TEXT,
    cause_category VARCHAR(32),
    impact TEXT,
    next_action TEXT,
    carry_forward_proposal TEXT,
    proposed_target_month DATE,
    contributor_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    linear_month_end_status VARCHAR(24) NOT NULL CHECK (
        linear_month_end_status IN ('CAPTURED', 'FETCH_FAILED', 'UNAVAILABLE')
    ),
    evidence_exception_reason TEXT,
    vendor_owner_declaration TEXT NOT NULL,
    UNIQUE (submission_id, deliverable_version_id),
    CONSTRAINT ck_outcome_target_month CHECK (
        proposed_target_month IS NULL
        OR EXTRACT(DAY FROM proposed_target_month) = 1
    )
);

CREATE TABLE delivery_submission_criterion_responses (
    id UUID PRIMARY KEY,
    outcome_id UUID NOT NULL REFERENCES deliverable_delivery_outcomes(id),
    criterion_id UUID NOT NULL REFERENCES delivery_acceptance_criteria(id),
    response_status VARCHAR(24) NOT NULL CHECK (
        response_status IN ('MET', 'NOT_MET', 'PARTIAL', 'NOT_APPLICABLE')
    ),
    response_text TEXT NOT NULL,
    UNIQUE (outcome_id, criterion_id)
);

CREATE TABLE delivery_evidence_items (
    id UUID PRIMARY KEY,
    outcome_id UUID REFERENCES deliverable_delivery_outcomes(id),
    clarification_id UUID,
    artifact_id UUID NOT NULL REFERENCES evidence_artifacts(id),
    evidence_type VARCHAR(40) NOT NULL CHECK (evidence_type IN (
        'RELEASE_BUILD', 'DEMO', 'TEST_REPORT', 'DESIGN_DOCUMENT',
        'REPOSITORY_REFERENCE', 'MONITORING_METRIC', 'API_PROOF',
        'BUSINESS_OUTCOME', 'LINEAR_SNAPSHOT', 'CLIENT_DEPENDENCY', 'OTHER'
    )),
    description TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_subject VARCHAR(255) NOT NULL,
    CONSTRAINT ck_evidence_item_parent CHECK (
        (outcome_id IS NOT NULL AND clarification_id IS NULL)
        OR (outcome_id IS NULL AND clarification_id IS NOT NULL)
    )
);

CREATE TABLE delivery_submission_responses (
    id UUID PRIMARY KEY,
    submission_id UUID NOT NULL REFERENCES delivery_submissions(id),
    deliverable_version_id UUID NOT NULL REFERENCES delivery_deliverable_versions(id),
    responds_to_id UUID REFERENCES delivery_submission_responses(id),
    response_version INTEGER NOT NULL CHECK (response_version > 0),
    response_text TEXT NOT NULL,
    represented_at TIMESTAMPTZ,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_subject VARCHAR(255) NOT NULL,
    UNIQUE (submission_id, deliverable_version_id, response_version)
);

CREATE TABLE certification_rounds (
    id UUID PRIMARY KEY,
    engagement_month_id UUID NOT NULL REFERENCES engagement_months(id),
    submission_id UUID NOT NULL REFERENCES delivery_submissions(id),
    round_number INTEGER NOT NULL CHECK (round_number > 0),
    status VARCHAR(24) NOT NULL CHECK (
        status IN ('OPEN', 'AWAITING_CLARIFICATION', 'COMPLETED', 'SUPERSEDED')
    ),
    supersedes_id UUID REFERENCES certification_rounds(id),
    policy_version_id UUID NOT NULL REFERENCES certification_policy_versions(id),
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    UNIQUE (submission_id, round_number),
    UNIQUE (supersedes_id),
    CONSTRAINT ck_certification_round_complete CHECK (
        (status = 'COMPLETED' AND completed_at IS NOT NULL)
        OR (status <> 'COMPLETED' AND completed_at IS NULL)
    )
);
CREATE UNIQUE INDEX uq_current_certification_round
    ON certification_rounds(submission_id)
    WHERE status IN ('OPEN', 'AWAITING_CLARIFICATION');

CREATE TABLE deliverable_certifications (
    id UUID PRIMARY KEY,
    round_id UUID NOT NULL REFERENCES certification_rounds(id),
    submission_id UUID NOT NULL REFERENCES delivery_submissions(id),
    deliverable_version_id UUID NOT NULL REFERENCES delivery_deliverable_versions(id),
    decision VARCHAR(40) NOT NULL CHECK (decision IN (
        'ACCEPTED', 'ACCEPTED_WITH_OBSERVATIONS', 'PARTIALLY_ACCEPTED',
        'DEFERRED_CLIENT_DEPENDENCY', 'DEFERRED_VENDOR_DEPENDENCY',
        'REJECTED', 'CANCELLED_BY_APPROVED_CHANGE', 'MORE_INFORMATION_REQUIRED'
    )),
    comment TEXT,
    cause TEXT,
    next_action TEXT,
    observations TEXT,
    accepted_scope TEXT,
    rejected_scope TEXT,
    aggregate_override_rationale TEXT,
    baseline_checksum VARCHAR(64) NOT NULL CHECK (
        baseline_checksum ~ '^[0-9a-f]{64}$'
    ),
    submission_checksum VARCHAR(64) NOT NULL CHECK (
        submission_checksum ~ '^[0-9a-f]{64}$'
    ),
    authority_snapshot JSONB NOT NULL,
    source VARCHAR(32) NOT NULL CHECK (
        source IN ('IN_APP', 'TRUSTED_MIGRATION', 'MANUAL_EVIDENCE')
    ),
    represented_at TIMESTAMPTZ,
    decided_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_by_subject VARCHAR(255) NOT NULL,
    action_hash VARCHAR(64) NOT NULL CHECK (action_hash ~ '^[0-9a-f]{64}$'),
    hash_schema_version INTEGER NOT NULL DEFAULT 1 CHECK (hash_schema_version > 0),
    UNIQUE (round_id, deliverable_version_id),
    CONSTRAINT ck_certification_detail CHECK (
        decision IN ('ACCEPTED', 'ACCEPTED_WITH_OBSERVATIONS')
        OR (comment IS NOT NULL AND cause IS NOT NULL AND next_action IS NOT NULL)
    ),
    CONSTRAINT ck_certification_observations CHECK (
        decision <> 'ACCEPTED_WITH_OBSERVATIONS' OR observations IS NOT NULL
    ),
    CONSTRAINT ck_certification_partial CHECK (
        decision <> 'PARTIALLY_ACCEPTED'
        OR (accepted_scope IS NOT NULL AND rejected_scope IS NOT NULL)
    )
);

CREATE TABLE certification_criterion_results (
    id UUID PRIMARY KEY,
    certification_id UUID NOT NULL REFERENCES deliverable_certifications(id),
    criterion_id UUID NOT NULL REFERENCES delivery_acceptance_criteria(id),
    result VARCHAR(24) NOT NULL CHECK (
        result IN ('ACCEPTED', 'REJECTED', 'PARTIAL', 'NOT_APPLICABLE')
    ),
    rationale TEXT,
    evidence_viewed JSONB NOT NULL DEFAULT '[]'::jsonb,
    UNIQUE (certification_id, criterion_id)
);

CREATE TABLE certification_clarifications (
    id UUID PRIMARY KEY,
    round_id UUID NOT NULL REFERENCES certification_rounds(id),
    submission_id UUID NOT NULL REFERENCES delivery_submissions(id),
    deliverable_version_id UUID NOT NULL REFERENCES delivery_deliverable_versions(id),
    clarification_number INTEGER NOT NULL CHECK (clarification_number > 0),
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('QUESTION', 'RESPONSE')),
    parent_clarification_id UUID REFERENCES certification_clarifications(id),
    message TEXT NOT NULL,
    requested_evidence JSONB NOT NULL DEFAULT '[]'::jsonb,
    sla_paused BOOLEAN NOT NULL DEFAULT FALSE,
    policy_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    represented_at TIMESTAMPTZ,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actor_subject VARCHAR(255) NOT NULL,
    UNIQUE (round_id, deliverable_version_id, clarification_number)
);

ALTER TABLE delivery_evidence_items
    ADD CONSTRAINT fk_delivery_evidence_clarification
        FOREIGN KEY (clarification_id) REFERENCES certification_clarifications(id);

CREATE TABLE carry_forward_links (
    id UUID PRIMARY KEY,
    certification_id UUID NOT NULL UNIQUE REFERENCES deliverable_certifications(id),
    origin_deliverable_id UUID NOT NULL REFERENCES delivery_deliverables(id),
    origin_deliverable_version_id UUID NOT NULL
        REFERENCES delivery_deliverable_versions(id),
    target_engagement_month_id UUID NOT NULL REFERENCES engagement_months(id),
    target_deliverable_id UUID REFERENCES delivery_deliverables(id),
    cause_owner VARCHAR(16) NOT NULL CHECK (
        cause_owner IN ('CLIENT', 'VENDOR', 'JOINT', 'EXTERNAL')
    ),
    next_action TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_subject VARCHAR(255) NOT NULL,
    UNIQUE (origin_deliverable_version_id, target_engagement_month_id)
);

CREATE TABLE monthly_certification_summaries (
    id UUID PRIMARY KEY,
    engagement_month_id UUID NOT NULL REFERENCES engagement_months(id),
    submission_id UUID NOT NULL REFERENCES delivery_submissions(id),
    round_id UUID NOT NULL REFERENCES certification_rounds(id),
    plan_version_id UUID NOT NULL REFERENCES delivery_plan_versions(id),
    baseline_id UUID NOT NULL REFERENCES delivery_plan_baselines(id),
    policy_version_id UUID NOT NULL REFERENCES certification_policy_versions(id),
    version INTEGER NOT NULL CHECK (version > 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('CURRENT', 'SUPERSEDED')),
    supersedes_id UUID REFERENCES monthly_certification_summaries(id),
    monthly_decision VARCHAR(40) NOT NULL CHECK (monthly_decision IN (
        'CERTIFIED', 'CERTIFIED_WITH_OBSERVATIONS',
        'PARTIALLY_CERTIFIED', 'NOT_CERTIFIED'
    )),
    observations TEXT,
    risks TEXT,
    manifest JSONB NOT NULL,
    checksum VARCHAR(64) NOT NULL CHECK (checksum ~ '^[0-9a-f]{64}$'),
    hash_algorithm VARCHAR(16) NOT NULL DEFAULT 'SHA-256'
        CHECK (hash_algorithm = 'SHA-256'),
    hash_schema_version INTEGER NOT NULL DEFAULT 1 CHECK (hash_schema_version > 0),
    authority_snapshot JSONB NOT NULL,
    represented_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_subject VARCHAR(255) NOT NULL,
    UNIQUE (engagement_month_id, version),
    UNIQUE (supersedes_id)
);
CREATE UNIQUE INDEX uq_current_certification_summary
    ON monthly_certification_summaries(engagement_month_id)
    WHERE status = 'CURRENT';

CREATE TABLE confirmation_eligibility_snapshots (
    id UUID PRIMARY KEY,
    engagement_month_id UUID NOT NULL REFERENCES engagement_months(id),
    policy_version_id UUID NOT NULL REFERENCES certification_policy_versions(id),
    eligible_confirmer_subject VARCHAR(255) NOT NULL,
    verified_email VARCHAR(320) NOT NULL,
    project_id UUID REFERENCES projects(id),
    sequence_number INTEGER NOT NULL CHECK (sequence_number > 0),
    authority_snapshot JSONB NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (id, eligible_confirmer_subject)
);

CREATE TABLE business_confirmation_requests (
    id UUID PRIMARY KEY,
    engagement_month_id UUID NOT NULL REFERENCES engagement_months(id),
    attendance_snapshot_id UUID REFERENCES attendance_snapshot_versions(id),
    plan_version_id UUID NOT NULL REFERENCES delivery_plan_versions(id),
    baseline_id UUID NOT NULL REFERENCES delivery_plan_baselines(id),
    certification_summary_id UUID NOT NULL REFERENCES monthly_certification_summaries(id),
    policy_version_id UUID NOT NULL REFERENCES certification_policy_versions(id),
    package_version_reference VARCHAR(255),
    version INTEGER NOT NULL CHECK (version > 0),
    status VARCHAR(32) NOT NULL CHECK (status IN (
        'DRAFT', 'QUEUED', 'SENT', 'AWAITING_RESPONSE', 'CONFIRMED',
        'CHANGES_REQUESTED', 'REJECTED', 'EXPIRED', 'CANCELLED', 'SUPERSEDED'
    )),
    transport_status VARCHAR(24) NOT NULL CHECK (
        transport_status IN (
            'NOT_CONFIGURED', 'QUEUED', 'SENT', 'DELIVERED', 'BOUNCED', 'FAILED'
        )
    ),
    supersedes_id UUID REFERENCES business_confirmation_requests(id),
    quorum_mode VARCHAR(24) NOT NULL CHECK (
        quorum_mode IN ('ANY_ONE', 'ALL', 'N_OF_M', 'ORDERED', 'PROJECT_SPECIFIC')
    ),
    quorum_required INTEGER NOT NULL CHECK (quorum_required > 0),
    recipient_snapshot JSONB NOT NULL,
    eligibility_snapshot JSONB NOT NULL,
    scope_manifest JSONB NOT NULL,
    scope_checksum VARCHAR(64) NOT NULL CHECK (scope_checksum ~ '^[0-9a-f]{64}$'),
    hash_algorithm VARCHAR(16) NOT NULL DEFAULT 'SHA-256'
        CHECK (hash_algorithm = 'SHA-256'),
    hash_schema_version INTEGER NOT NULL DEFAULT 1 CHECK (hash_schema_version > 0),
    optimistic_version BIGINT NOT NULL DEFAULT 0 CHECK (optimistic_version >= 0),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    due_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    created_by_subject VARCHAR(255) NOT NULL,
    UNIQUE (engagement_month_id, version),
    UNIQUE (supersedes_id),
    CONSTRAINT ck_confirmation_due CHECK (due_at > requested_at),
    CONSTRAINT ck_confirmation_completed CHECK (
        (status IN ('CONFIRMED', 'CHANGES_REQUESTED', 'REJECTED')
            AND completed_at IS NOT NULL)
        OR (status NOT IN ('CONFIRMED', 'CHANGES_REQUESTED', 'REJECTED')
            AND completed_at IS NULL)
    )
);
CREATE UNIQUE INDEX uq_current_confirmation_request
    ON business_confirmation_requests(engagement_month_id)
    WHERE status NOT IN ('CANCELLED', 'SUPERSEDED', 'EXPIRED');

CREATE TABLE confirmation_request_eligibility (
    request_id UUID NOT NULL REFERENCES business_confirmation_requests(id),
    eligibility_id UUID NOT NULL REFERENCES confirmation_eligibility_snapshots(id),
    eligible_confirmer_subject VARCHAR(255) NOT NULL,
    project_id UUID REFERENCES projects(id),
    sequence_number INTEGER NOT NULL CHECK (sequence_number > 0),
    PRIMARY KEY (request_id, eligible_confirmer_subject, sequence_number),
    FOREIGN KEY (eligibility_id, eligible_confirmer_subject)
        REFERENCES confirmation_eligibility_snapshots(id, eligible_confirmer_subject)
);

CREATE TABLE confirmation_secure_tokens (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES business_confirmation_requests(id),
    request_version INTEGER NOT NULL CHECK (request_version > 0),
    eligible_confirmer_subject VARCHAR(255) NOT NULL,
    token_hash VARCHAR(512) NOT NULL,
    token_salt VARCHAR(128) NOT NULL,
    hash_algorithm VARCHAR(32) NOT NULL CHECK (
        hash_algorithm IN ('PBKDF2-HMAC-SHA256')
    ),
    work_factor INTEGER NOT NULL CHECK (work_factor >= 100000),
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    consumed_by_subject VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (request_id, eligible_confirmer_subject),
    CONSTRAINT ck_token_consumption CHECK (
        (consumed_at IS NULL AND consumed_by_subject IS NULL)
        OR (consumed_at IS NOT NULL AND consumed_by_subject IS NOT NULL)
    )
);

CREATE TABLE business_confirmation_actions (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES business_confirmation_requests(id),
    request_version INTEGER NOT NULL CHECK (request_version > 0),
    token_id UUID REFERENCES confirmation_secure_tokens(id),
    actor_subject VARCHAR(255) NOT NULL,
    actor_authority_snapshot JSONB NOT NULL,
    project_id UUID REFERENCES projects(id),
    action VARCHAR(32) NOT NULL CHECK (
        action IN ('CONFIRM', 'REQUEST_CORRECTION', 'REJECT')
    ),
    comment TEXT,
    source VARCHAR(32) NOT NULL CHECK (
        source IN (
            'IN_APP', 'SECURE_EMAIL_LINK', 'VERIFIED_EMAIL_REPLY',
            'MANUAL_EVIDENCE', 'TRUSTED_MIGRATION'
        )
    ),
    verification_status VARCHAR(24) NOT NULL CHECK (
        verification_status IN ('VERIFIED', 'MANUAL_REVIEWED', 'MIGRATED')
    ),
    session_evidence_hash VARCHAR(64),
    represented_at TIMESTAMPTZ,
    action_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    action_hash VARCHAR(64) NOT NULL CHECK (action_hash ~ '^[0-9a-f]{64}$'),
    idempotency_key VARCHAR(160) NOT NULL,
    CONSTRAINT ck_confirmation_action_comment CHECK (
        action = 'CONFIRM' OR comment IS NOT NULL
    ),
    UNIQUE (request_id, actor_subject, project_id),
    UNIQUE (request_id, idempotency_key)
);

CREATE TABLE notification_outbox (
    id UUID PRIMARY KEY,
    engagement_month_id UUID NOT NULL REFERENCES engagement_months(id),
    event_type VARCHAR(80) NOT NULL,
    business_object_type VARCHAR(64) NOT NULL,
    business_object_id UUID NOT NULL,
    business_object_version INTEGER NOT NULL CHECK (business_object_version > 0),
    idempotency_key VARCHAR(200) NOT NULL UNIQUE,
    correlation_id UUID NOT NULL,
    causation_id UUID,
    template_key VARCHAR(80) NOT NULL,
    template_version INTEGER NOT NULL CHECK (template_version > 0),
    recipient_snapshot JSONB NOT NULL,
    subject_text TEXT NOT NULL,
    plain_text TEXT NOT NULL,
    html_text TEXT NOT NULL,
    attachment_manifest JSONB NOT NULL DEFAULT '[]'::jsonb,
    rendered_body_hash VARCHAR(64) NOT NULL CHECK (
        rendered_body_hash ~ '^[0-9a-f]{64}$'
    ),
    archive_manifest_hash VARCHAR(64) NOT NULL CHECK (
        archive_manifest_hash ~ '^[0-9a-f]{64}$'
    ),
    channel VARCHAR(16) NOT NULL DEFAULT 'EMAIL' CHECK (channel IN ('EMAIL', 'IN_APP')),
    provider_status VARCHAR(24) NOT NULL DEFAULT 'NOT_CONFIGURED' CHECK (
        provider_status IN ('NOT_CONFIGURED', 'ACTION_REQUIRED', 'CONFIGURED')
    ),
    transport_status VARCHAR(24) NOT NULL DEFAULT 'NOT_CONFIGURED' CHECK (
        transport_status IN (
            'NOT_CONFIGURED', 'QUEUED', 'SENDING', 'SENT', 'DELIVERED',
            'BOUNCED', 'FAILED', 'DEAD_LETTER'
        )
    ),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMPTZ,
    provider_message_id VARCHAR(255),
    provider_thread_id VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    bounced_at TIMESTAMPTZ
);

CREATE TABLE notification_delivery_attempts (
    id UUID PRIMARY KEY,
    outbox_id UUID NOT NULL REFERENCES notification_outbox(id),
    attempt_number INTEGER NOT NULL CHECK (attempt_number > 0),
    status VARCHAR(24) NOT NULL CHECK (
        status IN ('STARTED', 'RETRYABLE_FAILURE', 'PERMANENT_FAILURE', 'SENT')
    ),
    error_category VARCHAR(64),
    sanitized_error_code VARCHAR(64),
    provider_message_id VARCHAR(255),
    next_retry_at TIMESTAMPTZ,
    correlation_id UUID NOT NULL,
    attempted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (outbox_id, attempt_number)
);

CREATE TABLE inbound_confirmation_messages (
    id UUID PRIMARY KEY,
    engagement_month_id UUID REFERENCES engagement_months(id),
    request_id UUID REFERENCES business_confirmation_requests(id),
    provider_message_fingerprint VARCHAR(128) NOT NULL UNIQUE,
    provider_message_id VARCHAR(255),
    provider_thread_id VARCHAR(255),
    sender_address_hash VARCHAR(64) NOT NULL CHECK (
        sender_address_hash ~ '^[0-9a-f]{64}$'
    ),
    raw_reference VARCHAR(768),
    raw_sha256 VARCHAR(64) CHECK (raw_sha256 IS NULL OR raw_sha256 ~ '^[0-9a-f]{64}$'),
    in_reply_to_hash VARCHAR(64),
    references_hash VARCHAR(64),
    authentication_evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
    classified_intent VARCHAR(32) NOT NULL CHECK (classified_intent IN (
        'EXPLICIT_CONFIRM', 'EXPLICIT_CORRECTION', 'EXPLICIT_REJECT',
        'AMBIGUOUS', 'AUTO_REPLY', 'RECEIPT', 'FORWARDED', 'UNMATCHED', 'MALFORMED'
    )),
    status VARCHAR(32) NOT NULL CHECK (status IN (
        'RECORDED', 'QUARANTINED', 'MANUAL_REVIEW_REQUIRED', 'REVIEWED'
    )),
    provider_received_at TIMESTAMPTZ,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    correlation_id UUID NOT NULL,
    CONSTRAINT ck_inbound_non_approval CHECK (
        classified_intent NOT IN (
            'AUTO_REPLY', 'RECEIPT', 'FORWARDED', 'UNMATCHED', 'MALFORMED'
        )
        OR status IN ('QUARANTINED', 'MANUAL_REVIEW_REQUIRED')
    )
);

CREATE TABLE inbound_confirmation_reviews (
    id UUID PRIMARY KEY,
    inbound_message_id UUID NOT NULL REFERENCES inbound_confirmation_messages(id),
    reviewer_subject VARCHAR(255) NOT NULL,
    decision VARCHAR(24) NOT NULL CHECK (
        decision IN ('ACCEPT_INTERPRETATION', 'REJECT_INTERPRETATION', 'QUARANTINE')
    ),
    reasoning TEXT NOT NULL,
    reviewed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (inbound_message_id, reviewer_subject)
);

CREATE TABLE manual_confirmation_evidence (
    id UUID PRIMARY KEY,
    engagement_month_id UUID NOT NULL REFERENCES engagement_months(id),
    request_id UUID REFERENCES business_confirmation_requests(id),
    artifact_id UUID NOT NULL REFERENCES evidence_artifacts(id),
    evidence_format VARCHAR(16) NOT NULL CHECK (
        evidence_format IN ('EML', 'MSG', 'PDF', 'SCREENSHOT', 'PROVIDER_EXPORT')
    ),
    sender_address VARCHAR(320) NOT NULL,
    recipients JSONB NOT NULL,
    subject_text TEXT NOT NULL,
    message_id VARCHAR(255) NOT NULL,
    sent_or_received_at TIMESTAMPTZ NOT NULL,
    represented_decision VARCHAR(32) NOT NULL CHECK (
        represented_decision IN ('CONFIRM', 'REQUEST_CORRECTION', 'REJECT')
    ),
    file_hash VARCHAR(64) NOT NULL CHECK (file_hash ~ '^[0-9a-f]{64}$'),
    verification_status VARCHAR(24) NOT NULL DEFAULT 'PENDING_SECOND_REVIEW' CHECK (
        verification_status IN ('PENDING_SECOND_REVIEW', 'APPROVED', 'REJECTED')
    ),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    recorded_by_subject VARCHAR(255) NOT NULL,
    UNIQUE (engagement_month_id, file_hash)
);

CREATE TABLE manual_confirmation_evidence_reviews (
    id UUID PRIMARY KEY,
    manual_evidence_id UUID NOT NULL UNIQUE REFERENCES manual_confirmation_evidence(id),
    reviewer_subject VARCHAR(255) NOT NULL,
    decision VARCHAR(16) NOT NULL CHECK (decision IN ('APPROVE', 'REJECT')),
    reasoning TEXT NOT NULL,
    authority_snapshot JSONB NOT NULL,
    reviewed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE certification_readiness_runs (
    id UUID PRIMARY KEY,
    engagement_month_id UUID NOT NULL REFERENCES engagement_months(id),
    input_manifest JSONB NOT NULL,
    input_hash VARCHAR(64) NOT NULL CHECK (input_hash ~ '^[0-9a-f]{64}$'),
    status VARCHAR(24) NOT NULL CHECK (
        status IN ('READY_FOR_REQUEST', 'READY_FOR_F05', 'BLOCKED', 'INVALIDATED')
    ),
    ready_for_confirmation_request BOOLEAN NOT NULL,
    ready_for_f05_handoff BOOLEAN NOT NULL,
    evaluated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    evaluated_by_subject VARCHAR(255) NOT NULL,
    correlation_id UUID NOT NULL,
    UNIQUE (engagement_month_id, input_hash)
);

CREATE TABLE certification_readiness_results (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES certification_readiness_runs(id),
    pillar VARCHAR(32) NOT NULL CHECK (
        pillar IN ('ROSTER_ALLOCATION', 'ATTENDANCE', 'PLAN_LINEAR', 'CERTIFICATION', 'CONFIRMATION_F05')
    ),
    status VARCHAR(24) NOT NULL CHECK (
        status IN ('READY', 'BLOCKED', 'ACTION_REQUIRED', 'NOT_REQUIRED')
    ),
    source_object_type VARCHAR(64),
    source_object_id UUID,
    source_version VARCHAR(80),
    freshness VARCHAR(32),
    blocker_code VARCHAR(80),
    severity VARCHAR(16) NOT NULL CHECK (
        severity IN ('INFO', 'WARNING', 'ERROR', 'BLOCKING')
    ),
    owner_role VARCHAR(64),
    action_cta VARCHAR(255),
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE (run_id, pillar)
);

CREATE TABLE month_closures (
    id UUID PRIMARY KEY,
    engagement_month_id UUID NOT NULL REFERENCES engagement_months(id),
    version INTEGER NOT NULL CHECK (version > 0),
    confirmation_request_id UUID NOT NULL REFERENCES business_confirmation_requests(id),
    manifest JSONB NOT NULL,
    manifest_hash VARCHAR(64) NOT NULL CHECK (manifest_hash ~ '^[0-9a-f]{64}$'),
    hash_schema_version INTEGER NOT NULL DEFAULT 1 CHECK (hash_schema_version > 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('CURRENT', 'SUPERSEDED')),
    supersedes_id UUID REFERENCES month_closures(id),
    closed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    closed_by_subject VARCHAR(255) NOT NULL,
    UNIQUE (engagement_month_id, version),
    UNIQUE (supersedes_id)
);
CREATE UNIQUE INDEX uq_current_month_closure
    ON month_closures(engagement_month_id) WHERE status = 'CURRENT';

CREATE TABLE month_reopen_requests (
    id UUID PRIMARY KEY,
    engagement_month_id UUID NOT NULL REFERENCES engagement_months(id),
    closure_id UUID REFERENCES month_closures(id),
    status VARCHAR(24) NOT NULL CHECK (
        status IN ('REQUESTED', 'APPROVED', 'REJECTED', 'CANCELLED')
    ),
    reason TEXT NOT NULL,
    category VARCHAR(40) NOT NULL,
    impacted_records JSONB NOT NULL,
    package_or_invoice_submitted BOOLEAN NOT NULL,
    recipient_snapshot JSONB NOT NULL,
    risk_statement TEXT NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    requested_by_subject VARCHAR(255) NOT NULL,
    decided_at TIMESTAMPTZ,
    decided_by_subject VARCHAR(255),
    decision_reason TEXT,
    idempotency_key VARCHAR(160) NOT NULL,
    UNIQUE (engagement_month_id, idempotency_key),
    CONSTRAINT ck_reopen_decision CHECK (
        (status = 'REQUESTED' AND decided_at IS NULL AND decided_by_subject IS NULL)
        OR (status <> 'REQUESTED' AND decided_at IS NOT NULL
            AND decided_by_subject IS NOT NULL AND decision_reason IS NOT NULL)
    )
);
CREATE UNIQUE INDEX uq_pending_month_reopen
    ON month_reopen_requests(engagement_month_id)
    WHERE status = 'REQUESTED';

CREATE TABLE certification_invalidations (
    id UUID PRIMARY KEY,
    engagement_month_id UUID NOT NULL REFERENCES engagement_months(id),
    reopen_request_id UUID REFERENCES month_reopen_requests(id),
    object_type VARCHAR(64) NOT NULL,
    object_id UUID NOT NULL,
    reason_code VARCHAR(80) NOT NULL,
    downstream_contract VARCHAR(80) NOT NULL DEFAULT 'F05_NOT_CONFIGURED',
    status VARCHAR(24) NOT NULL CHECK (
        status IN ('ACTIVE', 'CLEARED', 'SUPERSEDED')
    ),
    correlation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_subject VARCHAR(255) NOT NULL,
    UNIQUE (object_type, object_id, reason_code, correlation_id)
);

CREATE TABLE certification_domain_events (
    id UUID PRIMARY KEY,
    engagement_month_id UUID NOT NULL REFERENCES engagement_months(id),
    event_type VARCHAR(100) NOT NULL,
    actor_type VARCHAR(16) NOT NULL CHECK (
        actor_type IN ('USER', 'SERVICE', 'INTEGRATION', 'MIGRATION')
    ),
    actor_subject VARCHAR(255) NOT NULL,
    subject_type VARCHAR(64) NOT NULL,
    subject_id UUID NOT NULL,
    subject_version INTEGER,
    correlation_id UUID NOT NULL,
    causation_id UUID,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    schema_version INTEGER NOT NULL DEFAULT 1 CHECK (schema_version > 0),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE certification_audit_events (
    id UUID PRIMARY KEY,
    engagement_month_id UUID,
    event_type VARCHAR(100) NOT NULL,
    actor_subject VARCHAR(255) NOT NULL,
    authority_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    object_type VARCHAR(64) NOT NULL,
    object_id UUID,
    object_version INTEGER,
    source VARCHAR(32) NOT NULL,
    reason TEXT,
    result VARCHAR(32) NOT NULL,
    evidence_references JSONB NOT NULL DEFAULT '[]'::jsonb,
    correlation_id UUID NOT NULL,
    policy_version_id UUID REFERENCES certification_policy_versions(id),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE certification_security_events (
    id UUID PRIMARY KEY,
    engagement_month_id UUID,
    event_type VARCHAR(100) NOT NULL,
    actor_subject_hash VARCHAR(64),
    object_type VARCHAR(64),
    object_id UUID,
    outcome VARCHAR(32) NOT NULL,
    redacted_facts JSONB NOT NULL DEFAULT '{}'::jsonb,
    correlation_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE certification_idempotency_keys (
    id UUID PRIMARY KEY,
    actor_subject VARCHAR(255) NOT NULL,
    operation VARCHAR(80) NOT NULL,
    scope_id UUID NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_hash VARCHAR(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    result_type VARCHAR(64) NOT NULL,
    result_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (actor_subject, operation, scope_id, idempotency_key)
);

CREATE OR REPLACE FUNCTION enforce_f04_submission_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM engagement_months month
        JOIN delivery_plans plan ON plan.engagement_month_id = month.id
        JOIN delivery_plan_versions version
          ON version.id = NEW.plan_version_id AND version.plan_id = plan.id
        JOIN delivery_plan_baselines baseline
          ON baseline.id = NEW.baseline_id AND baseline.plan_version_id = version.id
        JOIN certification_policy_versions policy
          ON policy.id = NEW.policy_version_id
         AND policy.engagement_id = month.engagement_id
        WHERE month.id = NEW.engagement_month_id
          AND version.state = 'FROZEN'
          AND plan.current_version_id = version.id
    ) THEN
        RAISE EXCEPTION 'Submission references a non-current or cross-scope baseline'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f04_submission_scope_gate
BEFORE INSERT OR UPDATE OF engagement_month_id, plan_version_id, baseline_id, policy_version_id
ON delivery_submissions
FOR EACH ROW EXECUTE FUNCTION enforce_f04_submission_scope();

CREATE OR REPLACE FUNCTION enforce_f04_outcome_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM delivery_submissions submission
        JOIN delivery_deliverable_versions deliverable
          ON deliverable.id = NEW.deliverable_version_id
        WHERE submission.id = NEW.submission_id
          AND deliverable.plan_version_id = submission.plan_version_id
    ) THEN
        RAISE EXCEPTION 'Outcome deliverable is outside the submission baseline'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f04_outcome_scope_gate
BEFORE INSERT OR UPDATE OF submission_id, deliverable_version_id
ON deliverable_delivery_outcomes
FOR EACH ROW EXECUTE FUNCTION enforce_f04_outcome_scope();

CREATE OR REPLACE FUNCTION enforce_f04_criterion_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM deliverable_delivery_outcomes outcome
        JOIN delivery_acceptance_criteria criterion
          ON criterion.id = NEW.criterion_id
        WHERE outcome.id = NEW.outcome_id
          AND criterion.deliverable_version_id = outcome.deliverable_version_id
    ) THEN
        RAISE EXCEPTION 'Criterion response is outside the outcome deliverable'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f04_criterion_scope_gate
BEFORE INSERT OR UPDATE OF outcome_id, criterion_id
ON delivery_submission_criterion_responses
FOR EACH ROW EXECUTE FUNCTION enforce_f04_criterion_scope();

CREATE OR REPLACE FUNCTION enforce_f04_certification_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM certification_rounds round
        JOIN delivery_submissions submission ON submission.id = round.submission_id
        JOIN deliverable_delivery_outcomes outcome
          ON outcome.submission_id = submission.id
         AND outcome.deliverable_version_id = NEW.deliverable_version_id
        WHERE round.id = NEW.round_id
          AND submission.id = NEW.submission_id
          AND submission.status IN ('SUBMITTED', 'UNDER_REVIEW')
    ) THEN
        RAISE EXCEPTION 'Certification is outside the submitted review round'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f04_certification_scope_gate
BEFORE INSERT OR UPDATE OF round_id, submission_id, deliverable_version_id
ON deliverable_certifications
FOR EACH ROW EXECUTE FUNCTION enforce_f04_certification_scope();

CREATE OR REPLACE FUNCTION f04_submission_transition_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status <> 'DRAFT' THEN
        RAISE EXCEPTION 'Submitted delivery evidence is immutable'
            USING ERRCODE = '55000';
    END IF;
    IF NEW.status NOT IN ('SUBMITTED', 'WITHDRAWN', 'SUPERSEDED')
       OR NEW.id <> OLD.id
       OR NEW.engagement_month_id <> OLD.engagement_month_id
       OR NEW.plan_version_id <> OLD.plan_version_id
       OR NEW.baseline_id <> OLD.baseline_id
       OR NEW.policy_version_id <> OLD.policy_version_id
       OR NEW.version <> OLD.version
       OR NEW.supersedes_id IS DISTINCT FROM OLD.supersedes_id
       OR NEW.summary <> OLD.summary
       OR NEW.vendor_declaration_accepted <> OLD.vendor_declaration_accepted
       OR NEW.declaration_text <> OLD.declaration_text
       OR NEW.represented_at IS DISTINCT FROM OLD.represented_at
       OR NEW.created_at <> OLD.created_at
       OR NEW.created_by_subject <> OLD.created_by_subject
       OR NEW.optimistic_version <> OLD.optimistic_version + 1 THEN
        RAISE EXCEPTION 'Invalid delivery submission transition'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f04_delivery_submission_transition_gate
BEFORE UPDATE ON delivery_submissions
FOR EACH ROW EXECUTE FUNCTION f04_submission_transition_guard();
CREATE TRIGGER f04_delivery_submission_delete_gate
BEFORE DELETE ON delivery_submissions
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

CREATE OR REPLACE FUNCTION f04_confirmation_transition_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    transition_allowed BOOLEAN;
BEGIN
    transition_allowed := CASE OLD.status
        WHEN 'DRAFT' THEN NEW.status IN ('QUEUED', 'CANCELLED')
        WHEN 'QUEUED' THEN NEW.status IN ('SENT', 'AWAITING_RESPONSE', 'CANCELLED', 'SUPERSEDED')
        WHEN 'SENT' THEN NEW.status IN ('AWAITING_RESPONSE', 'CANCELLED', 'SUPERSEDED')
        WHEN 'AWAITING_RESPONSE' THEN NEW.status IN (
            'CONFIRMED', 'CHANGES_REQUESTED', 'REJECTED',
            'EXPIRED', 'CANCELLED', 'SUPERSEDED'
        )
        WHEN 'CONFIRMED' THEN NEW.status = 'SUPERSEDED'
        WHEN 'CHANGES_REQUESTED' THEN NEW.status = 'SUPERSEDED'
        WHEN 'REJECTED' THEN NEW.status = 'SUPERSEDED'
        ELSE FALSE
    END;
    IF NOT transition_allowed
       OR NEW.id <> OLD.id
       OR NEW.engagement_month_id <> OLD.engagement_month_id
       OR NEW.plan_version_id <> OLD.plan_version_id
       OR NEW.baseline_id <> OLD.baseline_id
       OR NEW.certification_summary_id <> OLD.certification_summary_id
       OR NEW.policy_version_id <> OLD.policy_version_id
       OR NEW.version <> OLD.version
       OR NEW.scope_checksum <> OLD.scope_checksum
       OR NEW.scope_manifest <> OLD.scope_manifest
       OR NEW.recipient_snapshot <> OLD.recipient_snapshot
       OR NEW.eligibility_snapshot <> OLD.eligibility_snapshot
       OR NEW.quorum_mode <> OLD.quorum_mode
       OR NEW.quorum_required <> OLD.quorum_required
       OR NEW.optimistic_version <> OLD.optimistic_version + 1 THEN
        RAISE EXCEPTION 'Invalid confirmation request transition'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f04_confirmation_transition_gate
BEFORE UPDATE ON business_confirmation_requests
FOR EACH ROW EXECUTE FUNCTION f04_confirmation_transition_guard();
CREATE TRIGGER f04_confirmation_delete_gate
BEFORE DELETE ON business_confirmation_requests
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

CREATE OR REPLACE FUNCTION f04_summary_update_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status <> 'CURRENT' OR NEW.status <> 'SUPERSEDED'
       OR NEW.id <> OLD.id
       OR NEW.engagement_month_id <> OLD.engagement_month_id
       OR NEW.submission_id <> OLD.submission_id
       OR NEW.round_id <> OLD.round_id
       OR NEW.plan_version_id <> OLD.plan_version_id
       OR NEW.baseline_id <> OLD.baseline_id
       OR NEW.policy_version_id <> OLD.policy_version_id
       OR NEW.version <> OLD.version
       OR NEW.monthly_decision <> OLD.monthly_decision
       OR NEW.manifest <> OLD.manifest
       OR NEW.checksum <> OLD.checksum
       OR NEW.created_at <> OLD.created_at
       OR NEW.created_by_subject <> OLD.created_by_subject THEN
        RAISE EXCEPTION 'Certification summary content is immutable'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f04_summary_update_gate
BEFORE UPDATE ON monthly_certification_summaries
FOR EACH ROW EXECUTE FUNCTION f04_summary_update_guard();
CREATE TRIGGER f04_summary_delete_gate
BEFORE DELETE ON monthly_certification_summaries
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

CREATE OR REPLACE FUNCTION f04_manual_evidence_review_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.reviewer_subject = (
        SELECT recorded_by_subject
        FROM manual_confirmation_evidence
        WHERE id = NEW.manual_evidence_id
    ) THEN
        RAISE EXCEPTION 'Manual evidence requires a distinct second reviewer'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f04_manual_second_review_gate
BEFORE INSERT ON manual_confirmation_evidence_reviews
FOR EACH ROW EXECUTE FUNCTION f04_manual_evidence_review_guard();

CREATE OR REPLACE FUNCTION f04_notification_outbox_content_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id <> OLD.id
       OR NEW.engagement_month_id <> OLD.engagement_month_id
       OR NEW.event_type <> OLD.event_type
       OR NEW.business_object_type <> OLD.business_object_type
       OR NEW.business_object_id <> OLD.business_object_id
       OR NEW.business_object_version <> OLD.business_object_version
       OR NEW.idempotency_key <> OLD.idempotency_key
       OR NEW.correlation_id <> OLD.correlation_id
       OR NEW.causation_id IS DISTINCT FROM OLD.causation_id
       OR NEW.template_key <> OLD.template_key
       OR NEW.template_version <> OLD.template_version
       OR NEW.recipient_snapshot <> OLD.recipient_snapshot
       OR NEW.subject_text <> OLD.subject_text
       OR NEW.plain_text <> OLD.plain_text
       OR NEW.html_text <> OLD.html_text
       OR NEW.attachment_manifest <> OLD.attachment_manifest
       OR NEW.rendered_body_hash <> OLD.rendered_body_hash
       OR NEW.archive_manifest_hash <> OLD.archive_manifest_hash
       OR NEW.channel <> OLD.channel
       OR NEW.provider_status <> OLD.provider_status
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'Notification content is immutable'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f04_notification_outbox_content_gate
BEFORE UPDATE ON notification_outbox
FOR EACH ROW EXECUTE FUNCTION f04_notification_outbox_content_guard();
CREATE TRIGGER f04_notification_outbox_delete_gate
BEFORE DELETE ON notification_outbox
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

CREATE OR REPLACE FUNCTION f04_secure_token_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.consumed_at IS NOT NULL
       OR NEW.id <> OLD.id
       OR NEW.request_id <> OLD.request_id
       OR NEW.request_version <> OLD.request_version
       OR NEW.eligible_confirmer_subject <> OLD.eligible_confirmer_subject
       OR NEW.token_hash <> OLD.token_hash
       OR NEW.token_salt <> OLD.token_salt
       OR NEW.hash_algorithm <> OLD.hash_algorithm
       OR NEW.work_factor <> OLD.work_factor
       OR NEW.expires_at <> OLD.expires_at
       OR NEW.created_at <> OLD.created_at
       OR NEW.consumed_at IS NULL
       OR NEW.consumed_by_subject IS NULL THEN
        RAISE EXCEPTION 'Invalid secure token mutation'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f04_secure_token_update_gate
BEFORE UPDATE ON confirmation_secure_tokens
FOR EACH ROW EXECUTE FUNCTION f04_secure_token_guard();
CREATE TRIGGER f04_secure_token_delete_gate
BEFORE DELETE ON confirmation_secure_tokens
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

CREATE OR REPLACE FUNCTION f04_certification_round_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    valid_transition BOOLEAN;
BEGIN
    valid_transition := CASE OLD.status
        WHEN 'OPEN' THEN NEW.status IN (
            'AWAITING_CLARIFICATION', 'COMPLETED', 'SUPERSEDED')
        WHEN 'AWAITING_CLARIFICATION' THEN NEW.status = 'SUPERSEDED'
        ELSE FALSE
    END;
    IF NOT valid_transition
       OR NEW.id <> OLD.id
       OR NEW.engagement_month_id <> OLD.engagement_month_id
       OR NEW.submission_id <> OLD.submission_id
       OR NEW.round_number <> OLD.round_number
       OR NEW.supersedes_id IS DISTINCT FROM OLD.supersedes_id
       OR NEW.policy_version_id <> OLD.policy_version_id
       OR NEW.started_at <> OLD.started_at THEN
        RAISE EXCEPTION 'Invalid certification round transition'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f04_certification_round_update_gate
BEFORE UPDATE ON certification_rounds
FOR EACH ROW EXECUTE FUNCTION f04_certification_round_guard();
CREATE TRIGGER f04_certification_round_delete_gate
BEFORE DELETE ON certification_rounds
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

CREATE TRIGGER f04_certification_policy_immutable
BEFORE UPDATE OR DELETE ON certification_policy_versions
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TRIGGER f04_outcomes_immutable
BEFORE UPDATE OR DELETE ON deliverable_delivery_outcomes
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TRIGGER f04_submission_criteria_immutable
BEFORE UPDATE OR DELETE ON delivery_submission_criterion_responses
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TRIGGER f04_evidence_artifacts_immutable
BEFORE UPDATE OR DELETE ON evidence_artifacts
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TRIGGER f04_evidence_items_immutable
BEFORE UPDATE OR DELETE ON delivery_evidence_items
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TRIGGER f04_submission_responses_immutable
BEFORE UPDATE OR DELETE ON delivery_submission_responses
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TRIGGER f04_certifications_immutable
BEFORE UPDATE OR DELETE ON deliverable_certifications
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TRIGGER f04_certification_criteria_immutable
BEFORE UPDATE OR DELETE ON certification_criterion_results
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TRIGGER f04_clarifications_immutable
BEFORE UPDATE OR DELETE ON certification_clarifications
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TRIGGER f04_carry_forward_immutable
BEFORE UPDATE OR DELETE ON carry_forward_links
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TRIGGER f04_confirmation_eligibility_immutable
BEFORE UPDATE OR DELETE ON confirmation_eligibility_snapshots
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TRIGGER f04_confirmation_actions_immutable
BEFORE UPDATE OR DELETE ON business_confirmation_actions
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TRIGGER f04_notification_attempts_immutable
BEFORE UPDATE OR DELETE ON notification_delivery_attempts
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TRIGGER f04_inbound_messages_immutable
BEFORE UPDATE OR DELETE ON inbound_confirmation_messages
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TRIGGER f04_inbound_reviews_immutable
BEFORE UPDATE OR DELETE ON inbound_confirmation_reviews
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TRIGGER f04_manual_reviews_immutable
BEFORE UPDATE OR DELETE ON manual_confirmation_evidence_reviews
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TRIGGER f04_readiness_runs_immutable
BEFORE UPDATE OR DELETE ON certification_readiness_runs
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TRIGGER f04_readiness_results_immutable
BEFORE UPDATE OR DELETE ON certification_readiness_results
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TRIGGER f04_invalidations_immutable
BEFORE UPDATE OR DELETE ON certification_invalidations
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TRIGGER f04_domain_events_immutable
BEFORE UPDATE OR DELETE ON certification_domain_events
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TRIGGER f04_audit_events_immutable
BEFORE UPDATE OR DELETE ON certification_audit_events
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TRIGGER f04_security_events_immutable
BEFORE UPDATE OR DELETE ON certification_security_events
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TRIGGER f04_idempotency_immutable
BEFORE UPDATE OR DELETE ON certification_idempotency_keys
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

INSERT INTO permissions (id, code, description) VALUES
    ('10000000-0000-0000-0000-000000000030', 'certification.read', 'Read scoped delivery certification facts'),
    ('10000000-0000-0000-0000-000000000031', 'certification.submission.manage', 'Create versioned vendor delivery submission drafts'),
    ('10000000-0000-0000-0000-000000000032', 'certification.submission.submit', 'Submit vendor delivery evidence'),
    ('10000000-0000-0000-0000-000000000033', 'certification.item.decide', 'Decide assigned delivery certification items'),
    ('10000000-0000-0000-0000-000000000034', 'certification.summary.create', 'Create explicit monthly certification summaries'),
    ('10000000-0000-0000-0000-000000000035', 'certification.confirmation.request', 'Create scoped monthly confirmation requests'),
    ('10000000-0000-0000-0000-000000000036', 'certification.confirmation.act', 'Act as an eligible confirmation authority'),
    ('10000000-0000-0000-0000-000000000037', 'certification.inbound.review', 'Review restricted inbound or manual confirmation evidence'),
    ('10000000-0000-0000-0000-000000000038', 'certification.reopen.request', 'Request a reasoned month reopen'),
    ('10000000-0000-0000-0000-000000000039', 'certification.reopen.approve', 'Approve or reject a month reopen'),
    ('10000000-0000-0000-0000-000000000040', 'certification.close', 'Close a confirmed F04 month when downstream policy allows'),
    ('10000000-0000-0000-0000-000000000041', 'certification.outbox.replay', 'Replay failed provider-neutral notification work');

INSERT INTO role_permissions (role_id, permission_id)
SELECT role.id, permission.id
FROM roles role
CROSS JOIN permissions permission
WHERE
    (role.code IN ('ORG_ADMIN', 'ENGAGEMENT_ADMIN')
      AND permission.code IN (
        'certification.read', 'certification.summary.create',
        'certification.confirmation.request', 'certification.inbound.review',
        'certification.reopen.request', 'certification.reopen.approve',
        'certification.close', 'certification.outbox.replay'
      ))
    OR (role.code = 'VENDOR_MANAGER'
      AND permission.code IN (
        'certification.read', 'certification.submission.manage',
        'certification.submission.submit'
      ))
    OR (role.code = 'CLIENT_PRODUCT_OWNER'
      AND permission.code IN (
        'certification.read', 'certification.item.decide',
        'certification.summary.create', 'certification.confirmation.act',
        'certification.reopen.request'
      ))
    OR (role.code = 'AUDITOR_READONLY'
      AND permission.code = 'certification.read')
ON CONFLICT DO NOTHING;
