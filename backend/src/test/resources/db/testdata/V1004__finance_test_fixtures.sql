-- F05-only identities. Business facts are created transactionally per test.

INSERT INTO user_profiles
    (id, identity_subject, email, display_name, status, principal_type)
VALUES
    ('00000000-0000-0000-0000-000000000231',
     'user-procurement', 'procurement-reviewer@example.test',
     'Paula Procurement', 'ACTIVE', 'HUMAN'),
    ('00000000-0000-0000-0000-000000000232',
     'user-procurement-second', 'procurement-second@example.test',
     'Priya Second Approver', 'ACTIVE', 'HUMAN'),
    ('00000000-0000-0000-0000-000000000233',
     'user-finance-ap', 'finance-ap@example.test',
     'Finn Finance AP', 'ACTIVE', 'HUMAN');

INSERT INTO memberships
    (id, user_profile_id, organization_id, role_code, status,
     valid_from, valid_to)
VALUES
    ('00000000-0000-0000-0000-000000000331',
     '00000000-0000-0000-0000-000000000231',
     '00000000-0000-0000-0000-000000000103',
     'PROCUREMENT_REVIEWER', 'ACTIVE', '2020-01-01', NULL),
    ('00000000-0000-0000-0000-000000000332',
     '00000000-0000-0000-0000-000000000232',
     '00000000-0000-0000-0000-000000000103',
     'PROCUREMENT_REVIEWER', 'ACTIVE', '2020-01-01', NULL),
    ('00000000-0000-0000-0000-000000000333',
     '00000000-0000-0000-0000-000000000233',
     '00000000-0000-0000-0000-000000000103',
     'FINANCE_AP', 'ACTIVE', '2020-01-01', NULL);

INSERT INTO role_assignments
    (id, user_profile_id, organization_id, role_id, scope_type, scope_id,
     status, valid_from, valid_to)
VALUES
    ('12000000-0000-0000-0000-000000000031',
     '00000000-0000-0000-0000-000000000231',
     '00000000-0000-0000-0000-000000000103',
     '11000000-0000-0000-0000-000000000007',
     'ENGAGEMENT', '00000000-0000-0000-0000-000000000401',
     'ACTIVE', '2020-01-01', NULL),
    ('12000000-0000-0000-0000-000000000032',
     '00000000-0000-0000-0000-000000000232',
     '00000000-0000-0000-0000-000000000103',
     '11000000-0000-0000-0000-000000000007',
     'ENGAGEMENT', '00000000-0000-0000-0000-000000000401',
     'ACTIVE', '2020-01-01', NULL),
    ('12000000-0000-0000-0000-000000000033',
     '00000000-0000-0000-0000-000000000233',
     '00000000-0000-0000-0000-000000000103',
     '11000000-0000-0000-0000-000000000008',
     'ENGAGEMENT', '00000000-0000-0000-0000-000000000401',
     'ACTIVE', '2020-01-01', NULL);
