INSERT INTO linear_connections
    (id, engagement_id, provider_organization_id, display_name, status,
     provider_registration_status, credential_secret_ref, webhook_secret_ref,
     provider_team_id)
VALUES
    ('00000000-0000-0000-0000-000000001101',
     '00000000-0000-0000-0000-000000000401',
     'linear-test-organization',
     'Recorded Linear fixture',
     'CONNECTED',
     'EXTERNALLY_BLOCKED',
     'secret://local-fixture/linear/oauth',
     'secret://local-fixture/linear/webhook',
     'linear-team-a'),
    ('00000000-0000-0000-0000-000000001102',
     '00000000-0000-0000-0000-000000000401',
     'linear-test-organization-b',
     'Recorded Linear fixture B',
     'CONNECTED',
     'EXTERNALLY_BLOCKED',
     'secret://local-fixture/linear/oauth-b',
     'secret://local-fixture/linear/webhook-b',
     'linear-team-b');

INSERT INTO linear_state_mappings
    (connection_id, mapping_version, provider_state_type,
     provider_state_category, normalized_state)
VALUES
    ('00000000-0000-0000-0000-000000001101', 1, 'backlog', '', 'BACKLOG'),
    ('00000000-0000-0000-0000-000000001101', 1, 'unstarted', '', 'UNSTARTED'),
    ('00000000-0000-0000-0000-000000001101', 1, 'started', '', 'STARTED'),
    ('00000000-0000-0000-0000-000000001101', 1, 'completed', '', 'COMPLETED'),
    ('00000000-0000-0000-0000-000000001101', 1, 'canceled', '', 'CANCELED'),
    ('00000000-0000-0000-0000-000000001102', 1, 'unstarted', '', 'UNSTARTED'),
    ('00000000-0000-0000-0000-000000001102', 1, 'completed', '', 'COMPLETED');

INSERT INTO linear_recorded_issue_metadata
    (connection_id, linear_issue_uuid, provider_organization_id, provider_team_id,
     identifier, issue_url, title, provider_state_id, provider_state_name,
     provider_state_type, provider_state_category, provider_updated_at, payload_hash)
VALUES
    ('00000000-0000-0000-0000-000000001101',
     '00000000-0000-0000-0000-000000001201',
     'linear-test-organization', 'linear-team-a', 'TEAM-123',
     'https://linear.app/test/issue/TEAM-123', 'Recorded issue',
     'state-unstarted', 'Todo', 'unstarted', '',
     '2026-07-01T00:00:00Z', repeat('a', 64)),
    ('00000000-0000-0000-0000-000000001102',
     '00000000-0000-0000-0000-000000001202',
     'linear-test-organization-b', 'linear-team-b', 'OTHER-456',
     'https://linear.app/test/issue/OTHER-456', 'Recorded issue B',
     'state-unstarted', 'Todo', 'unstarted', '',
     '2026-07-01T00:00:00Z', repeat('b', 64));

INSERT INTO user_profiles (id, identity_subject, email, display_name, status)
VALUES
    ('00000000-0000-0000-0000-000000000220',
     'user-approver', 'approver@reliance.example', 'Priya Approver', 'ACTIVE'),
    ('00000000-0000-0000-0000-000000000221',
     'user-approver-2', 'approver2@reliance.example', 'Rahul Approver', 'ACTIVE');

INSERT INTO memberships
    (id, user_profile_id, organization_id, role_code, status, valid_from, valid_to)
VALUES
    ('00000000-0000-0000-0000-000000000320',
     '00000000-0000-0000-0000-000000000220',
     '00000000-0000-0000-0000-000000000102',
     'CLIENT_PRODUCT_OWNER', 'ACTIVE', '2020-01-01', NULL),
    ('00000000-0000-0000-0000-000000000321',
     '00000000-0000-0000-0000-000000000221',
     '00000000-0000-0000-0000-000000000102',
     'CLIENT_PRODUCT_OWNER', 'ACTIVE', '2020-01-01', NULL);

INSERT INTO role_assignments
    (id, user_profile_id, organization_id, role_id, scope_type, scope_id,
     status, valid_from, valid_to)
VALUES
    ('12000000-0000-0000-0000-000000000020',
     '00000000-0000-0000-0000-000000000220',
     '00000000-0000-0000-0000-000000000102',
     '11000000-0000-0000-0000-000000000004',
     'ENGAGEMENT', '00000000-0000-0000-0000-000000000401',
     'ACTIVE', '2020-01-01', NULL),
    ('12000000-0000-0000-0000-000000000021',
     '00000000-0000-0000-0000-000000000221',
     '00000000-0000-0000-0000-000000000102',
     '11000000-0000-0000-0000-000000000004',
     'ENGAGEMENT', '00000000-0000-0000-0000-000000000401',
     'ACTIVE', '2020-01-01', NULL);
