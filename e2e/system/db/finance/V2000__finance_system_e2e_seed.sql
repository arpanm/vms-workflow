-- Synthetic upstream F04 contract for the real-system F05 Playwright runner.
-- This location is included only by scripts/run-finance-system-e2e.mjs.

INSERT INTO delivery_plans(
    id, engagement_month_id, created_by_subject
) VALUES (
    'e2000000-0000-0000-0000-000000000010',
    '00000000-0000-0000-0000-000000000602',
    'SYSTEM:E2E'
);

INSERT INTO delivery_plan_versions(
    id, plan_id, version, state, title, summary, business_outcomes,
    coordinator_subject, baseline_type, quorum_mode, quorum_required,
    checksum, optimistic_version, submitted_at, frozen_at,
    created_by_subject
) VALUES (
    'e2000000-0000-0000-0000-000000000011',
    'e2000000-0000-0000-0000-000000000010',
    1, 'FROZEN', 'System E2E frozen plan',
    'Synthetic immutable prerequisite for real-system finance testing',
    'Exercise the packaged Spring and PostgreSQL finance vertical',
    'user-arrow', 'ON_TIME', 'ANY_ONE', 1, repeat('1', 64), 1,
    CURRENT_TIMESTAMP - INTERVAL '10 days',
    CURRENT_TIMESTAMP - INTERVAL '9 days', 'SYSTEM:E2E'
);

UPDATE delivery_plans
SET current_version_id = 'e2000000-0000-0000-0000-000000000011'
WHERE id = 'e2000000-0000-0000-0000-000000000010';

INSERT INTO delivery_plan_baselines(
    id, plan_version_id, checksum, deliverable_count
) VALUES (
    'e2000000-0000-0000-0000-000000000012',
    'e2000000-0000-0000-0000-000000000011',
    repeat('1', 64), 0
);

INSERT INTO certification_policy_versions(
    id, engagement_id, version, status, attendance_required,
    separation_of_duties_required, monthly_decision_required,
    manual_second_review_required, quorum_mode, quorum_required,
    token_ttl_seconds, confirmation_due_seconds, reminder_policy,
    evidence_policy, recipient_policy, retention_policy, policy_hash,
    created_by_subject
) VALUES (
    'e2000000-0000-0000-0000-000000000013',
    '00000000-0000-0000-0000-000000000401',
    1, 'ACTIVE', false, true, true, true, 'ANY_ONE', 1,
    259200, 432000, '{}'::jsonb, '{}'::jsonb, '{}'::jsonb,
    '{}'::jsonb, repeat('2', 64), 'SYSTEM:E2E'
);

INSERT INTO delivery_submissions(
    id, engagement_month_id, plan_version_id, baseline_id,
    policy_version_id, version, status, summary,
    vendor_declaration_accepted, declaration_text, checksum,
    optimistic_version, submitted_at, created_by_subject
) VALUES (
    'e2000000-0000-0000-0000-000000000014',
    '00000000-0000-0000-0000-000000000602',
    'e2000000-0000-0000-0000-000000000011',
    'e2000000-0000-0000-0000-000000000012',
    'e2000000-0000-0000-0000-000000000013',
    1, 'UNDER_REVIEW', 'System E2E submitted delivery evidence',
    true, 'Synthetic upstream declaration', repeat('3', 64), 1,
    CURRENT_TIMESTAMP - INTERVAL '8 days', 'SYSTEM:E2E'
);

INSERT INTO certification_rounds(
    id, engagement_month_id, submission_id, round_number, status,
    policy_version_id, started_at, completed_at
) VALUES (
    'e2000000-0000-0000-0000-000000000015',
    '00000000-0000-0000-0000-000000000602',
    'e2000000-0000-0000-0000-000000000014',
    1, 'COMPLETED',
    'e2000000-0000-0000-0000-000000000013',
    CURRENT_TIMESTAMP - INTERVAL '7 days',
    CURRENT_TIMESTAMP - INTERVAL '6 days'
);

INSERT INTO monthly_certification_summaries(
    id, engagement_month_id, submission_id, round_id, plan_version_id,
    baseline_id, policy_version_id, version, status, monthly_decision,
    observations, risks, manifest, checksum, authority_snapshot,
    created_by_subject
) VALUES (
    'e2000000-0000-0000-0000-000000000016',
    '00000000-0000-0000-0000-000000000602',
    'e2000000-0000-0000-0000-000000000014',
    'e2000000-0000-0000-0000-000000000015',
    'e2000000-0000-0000-0000-000000000011',
    'e2000000-0000-0000-0000-000000000012',
    'e2000000-0000-0000-0000-000000000013',
    1, 'CURRENT', 'CERTIFIED',
    'Synthetic system-E2E certification', 'No synthetic risk',
    '{"fixture":"real-spring-postgres"}'::jsonb, repeat('4', 64),
    '{"permission":"certification.summary.create"}'::jsonb,
    'SYSTEM:E2E'
);

INSERT INTO business_confirmation_requests(
    id, engagement_month_id, plan_version_id, baseline_id,
    certification_summary_id, policy_version_id, version, status,
    transport_status, quorum_mode, quorum_required, recipient_snapshot,
    eligibility_snapshot, scope_manifest, scope_checksum,
    optimistic_version, requested_at, due_at, completed_at,
    created_by_subject
) VALUES (
    'e2000000-0000-0000-0000-000000000002',
    '00000000-0000-0000-0000-000000000602',
    'e2000000-0000-0000-0000-000000000011',
    'e2000000-0000-0000-0000-000000000012',
    'e2000000-0000-0000-0000-000000000016',
    'e2000000-0000-0000-0000-000000000013',
    1, 'CONFIRMED', 'NOT_CONFIGURED', 'ANY_ONE', 1,
    '[]'::jsonb, '[]'::jsonb,
    '{"fixture":"real-spring-postgres"}'::jsonb, repeat('5', 64), 1,
    CURRENT_TIMESTAMP - INTERVAL '5 days',
    CURRENT_TIMESTAMP + INTERVAL '2 days',
    CURRENT_TIMESTAMP - INTERVAL '4 days', 'SYSTEM:E2E'
);

INSERT INTO certification_readiness_runs(
    id, engagement_month_id, input_manifest, input_hash, status,
    ready_for_confirmation_request, ready_for_f05_handoff,
    evaluated_by_subject, correlation_id
) VALUES (
    'e2000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000602',
    '{"fixture":"real-spring-postgres","schema":"finance-system-readiness-v1"}'::jsonb,
    '5f09528abb32ca1a9866b51f99bc2d54ffe8e2091a74c19d67503ff4b0dfe604',
    'READY_FOR_F05', true, true, 'SYSTEM:E2E',
    'e2000000-0000-0000-0000-000000000090'
);

INSERT INTO certification_readiness_results(
    id, run_id, pillar, status, source_object_type, source_object_id,
    source_version, freshness, severity, owner_role, action_cta, details
) VALUES
    ('e2000000-0000-0000-0000-000000000021',
     'e2000000-0000-0000-0000-000000000001',
     'ROSTER_ALLOCATION', 'READY', 'SYSTEM_E2E_SOURCE',
     'e2000000-0000-0000-0000-000000000031', '1', 'CURRENT',
     'INFO', 'VENDOR_MANAGER', 'None', '{"fixture":true}'::jsonb),
    ('e2000000-0000-0000-0000-000000000022',
     'e2000000-0000-0000-0000-000000000001',
     'ATTENDANCE', 'READY', 'SYSTEM_E2E_SOURCE',
     'e2000000-0000-0000-0000-000000000032', '1', 'CURRENT',
     'INFO', 'VENDOR_MANAGER', 'None', '{"fixture":true}'::jsonb),
    ('e2000000-0000-0000-0000-000000000023',
     'e2000000-0000-0000-0000-000000000001',
     'PLAN_LINEAR', 'READY', 'SYSTEM_E2E_SOURCE',
     'e2000000-0000-0000-0000-000000000033', '1', 'CURRENT',
     'INFO', 'VENDOR_MANAGER', 'None', '{"fixture":true}'::jsonb),
    ('e2000000-0000-0000-0000-000000000024',
     'e2000000-0000-0000-0000-000000000001',
     'CERTIFICATION', 'READY', 'SYSTEM_E2E_SOURCE',
     'e2000000-0000-0000-0000-000000000034', '1', 'CURRENT',
     'INFO', 'CLIENT_PRODUCT_OWNER', 'None', '{"fixture":true}'::jsonb),
    ('e2000000-0000-0000-0000-000000000025',
     'e2000000-0000-0000-0000-000000000001',
     'CONFIRMATION_F05', 'READY', 'SYSTEM_E2E_SOURCE',
     'e2000000-0000-0000-0000-000000000035', '1', 'CURRENT',
     'INFO', 'CLIENT_PRODUCT_OWNER', 'None', '{"fixture":true}'::jsonb);

INSERT INTO f05_certification_handoffs(
    id, engagement_month_id, confirmation_request_id, readiness_run_id,
    package_manifest, package_hash, status, created_by_subject,
    correlation_id
) VALUES (
    'e2000000-0000-0000-0000-000000000003',
    '00000000-0000-0000-0000-000000000602',
    'e2000000-0000-0000-0000-000000000002',
    'e2000000-0000-0000-0000-000000000001',
    '{
      "confirmationRequestId":"e2000000-0000-0000-0000-000000000002",
      "engagementMonthId":"00000000-0000-0000-0000-000000000602",
      "readinessRunId":"e2000000-0000-0000-0000-000000000001",
      "schema":"f04-f05-handoff-v1"
    }'::jsonb,
    '6ff32c54faad9eb07ba479019d148cdcd2a2a2ca84d186e291440ed58f7bb295',
    'READY_LOCAL', 'SYSTEM:E2E',
    'e2000000-0000-0000-0000-000000000091'
);
