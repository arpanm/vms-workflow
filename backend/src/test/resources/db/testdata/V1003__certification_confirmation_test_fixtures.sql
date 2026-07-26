-- F04-only deterministic identities. Workflow facts are created per test so that
-- frozen F03 baselines and F04 version/idempotency assertions remain isolated.

INSERT INTO user_profiles
    (id, identity_subject, email, display_name, status, principal_type)
VALUES
    ('00000000-0000-0000-0000-000000000222',
     'user-governance', 'governance@reliance.example',
     'Grace Governance', 'ACTIVE', 'HUMAN'),
    ('00000000-0000-0000-0000-000000000223',
     'user-sod', 'dual-authority@example.test',
     'Sam Dual Authority', 'ACTIVE', 'HUMAN'),
    ('00000000-0000-0000-0000-000000000224',
     'user-project-b', 'project-b-owner@reliance.example',
     'Parker Project B', 'ACTIVE', 'HUMAN'),
    ('00000000-0000-0000-0000-000000000225',
     'service-inbound', 'inbound-service@example.test',
     'Inbound Integration Service', 'ACTIVE', 'SERVICE'),
    ('00000000-0000-0000-0000-000000000226',
     'user-reviewer', 'reviewer@reliance.example',
     'Rita Restricted Reviewer', 'ACTIVE', 'HUMAN');

INSERT INTO memberships
    (id, user_profile_id, organization_id, role_code, status, valid_from, valid_to)
VALUES
    ('00000000-0000-0000-0000-000000000322',
     '00000000-0000-0000-0000-000000000222',
     '00000000-0000-0000-0000-000000000102',
     'ORG_ADMIN', 'ACTIVE', '2020-01-01', NULL),
    ('00000000-0000-0000-0000-000000000323',
     '00000000-0000-0000-0000-000000000223',
     '00000000-0000-0000-0000-000000000101',
     'VENDOR_MANAGER', 'ACTIVE', '2020-01-01', NULL),
    ('00000000-0000-0000-0000-000000000324',
     '00000000-0000-0000-0000-000000000223',
     '00000000-0000-0000-0000-000000000102',
     'CLIENT_PRODUCT_OWNER', 'ACTIVE', '2020-01-01', NULL),
    ('00000000-0000-0000-0000-000000000325',
     '00000000-0000-0000-0000-000000000224',
     '00000000-0000-0000-0000-000000000102',
     'CLIENT_PRODUCT_OWNER', 'ACTIVE', '2020-01-01', NULL),
    ('00000000-0000-0000-0000-000000000326',
     '00000000-0000-0000-0000-000000000225',
     '00000000-0000-0000-0000-000000000102',
     'ORG_ADMIN', 'ACTIVE', '2020-01-01', NULL),
    ('00000000-0000-0000-0000-000000000327',
     '00000000-0000-0000-0000-000000000226',
     '00000000-0000-0000-0000-000000000102',
     'ORG_ADMIN', 'ACTIVE', '2020-01-01', NULL);

INSERT INTO role_assignments
    (id, user_profile_id, organization_id, role_id, scope_type, scope_id,
     status, valid_from, valid_to)
VALUES
    ('12000000-0000-0000-0000-000000000022',
     '00000000-0000-0000-0000-000000000222',
     '00000000-0000-0000-0000-000000000102',
     '11000000-0000-0000-0000-000000000001',
     'ENGAGEMENT', '00000000-0000-0000-0000-000000000401',
     'ACTIVE', '2020-01-01', NULL),
    ('12000000-0000-0000-0000-000000000023',
     '00000000-0000-0000-0000-000000000223',
     '00000000-0000-0000-0000-000000000101',
     '11000000-0000-0000-0000-000000000003',
     'ENGAGEMENT', '00000000-0000-0000-0000-000000000401',
     'ACTIVE', '2020-01-01', NULL),
    ('12000000-0000-0000-0000-000000000024',
     '00000000-0000-0000-0000-000000000223',
     '00000000-0000-0000-0000-000000000102',
     '11000000-0000-0000-0000-000000000004',
     'ENGAGEMENT', '00000000-0000-0000-0000-000000000401',
     'ACTIVE', '2020-01-01', NULL),
    ('12000000-0000-0000-0000-000000000025',
     '00000000-0000-0000-0000-000000000224',
     '00000000-0000-0000-0000-000000000102',
     '11000000-0000-0000-0000-000000000004',
     'PROJECT', '00000000-0000-0000-0000-000000000501',
     'ACTIVE', '2020-01-01', NULL),
    ('12000000-0000-0000-0000-000000000026',
     '00000000-0000-0000-0000-000000000225',
     '00000000-0000-0000-0000-000000000102',
     '11000000-0000-0000-0000-000000000001',
     'ENGAGEMENT', '00000000-0000-0000-0000-000000000401',
     'ACTIVE', '2020-01-01', NULL),
    ('12000000-0000-0000-0000-000000000027',
     '00000000-0000-0000-0000-000000000226',
     '00000000-0000-0000-0000-000000000102',
     '11000000-0000-0000-0000-000000000001',
     'ENGAGEMENT', '00000000-0000-0000-0000-000000000401',
     'ACTIVE', '2020-01-01', NULL);

INSERT INTO evidence_artifacts
    (id, engagement_id, engagement_month_id, artifact_kind, object_key,
     object_version, safe_name, declared_mime_type, sniffed_mime_type,
     size_bytes, sha256, classification, scan_status, source,
     uploader_subject, provider_status)
VALUES
    ('00000000-0000-0000-0000-000000000904',
     '00000000-0000-0000-0000-000000000401',
     '00000000-0000-0000-0000-000000000602',
     'OBJECT', 'test/f04/immutable-test-report.pdf', 'fixture-v1',
     'immutable-test-report.pdf', 'application/pdf', 'application/pdf',
     128, repeat('9', 64), 'INTERNAL', 'PASSED', 'VENDOR',
     'user-arrow', 'NOT_CONFIGURED');
