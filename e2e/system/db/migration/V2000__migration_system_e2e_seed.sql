-- Explicit two-organization F06 sign-off chain for the real-system runner.
-- Production migrations and the ordinary synthetic fixtures are still applied;
-- this location is included only by run-migration-system-e2e.mjs.

INSERT INTO role_assignments
    (id, user_profile_id, organization_id, role_id, scope_type, scope_id,
     status, valid_from, valid_to)
SELECT
    'e6000000-0000-0000-0000-000000000001',
    profile.id,
    '00000000-0000-0000-0000-000000000101',
    role.id,
    'ENGAGEMENT',
    '00000000-0000-0000-0000-000000000401',
    'ACTIVE',
    DATE '2020-01-01',
    NULL
FROM user_profiles profile
JOIN roles role ON role.code = 'MIGRATION_LEAD'
WHERE profile.identity_subject = 'user-arrow'
ON CONFLICT DO NOTHING;

INSERT INTO role_assignments
    (id, user_profile_id, organization_id, role_id, scope_type, scope_id,
     status, valid_from, valid_to)
SELECT
    'e6000000-0000-0000-0000-000000000002',
    profile.id,
    '00000000-0000-0000-0000-000000000102',
    role.id,
    'ENGAGEMENT',
    '00000000-0000-0000-0000-000000000401',
    'ACTIVE',
    DATE '2020-01-01',
    NULL
FROM user_profiles profile
JOIN roles role ON role.code = 'GOVERNANCE_REVIEWER'
WHERE profile.identity_subject = 'user-governance'
ON CONFLICT DO NOTHING;
