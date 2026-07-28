-- Explicit F06 segregation-of-duties assignments. Generic administrator
-- authority is intentionally insufficient for either migration sign-off role.
INSERT INTO role_assignments
    (id, user_profile_id, organization_id, role_id, scope_type, scope_id,
     status, valid_from, valid_to)
VALUES
    ('12000000-0000-0000-0000-000000000040',
     '00000000-0000-0000-0000-000000000201',
     '00000000-0000-0000-0000-000000000101',
     '11000000-0000-0000-0000-000000000012',
     'ENGAGEMENT', '00000000-0000-0000-0000-000000000401',
     'ACTIVE', '2020-01-01', NULL),
    ('12000000-0000-0000-0000-000000000041',
     '00000000-0000-0000-0000-000000000222',
     '00000000-0000-0000-0000-000000000102',
     '11000000-0000-0000-0000-000000000009',
     'ENGAGEMENT', '00000000-0000-0000-0000-000000000401',
     'ACTIVE', '2020-01-01', NULL);
