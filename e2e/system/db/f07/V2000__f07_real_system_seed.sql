-- Local-only deterministic identities and provider recordings for the F07
-- real-system Playwright catalog. Live provider acceptance remains external.
INSERT INTO user_profiles
    (id, identity_subject, email, display_name, status, principal_type)
VALUES
    ('72000000-0000-0000-0000-000000000001',
     'user-e2e-employee', 'e2e.employee@arrowfoundry.example',
     'E2E Employee', 'ACTIVE', 'HUMAN');

INSERT INTO memberships
    (id, user_profile_id, organization_id, role_code, status,
     valid_from, valid_to)
VALUES
    ('72000000-0000-0000-0000-000000000002',
     '72000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000101',
     'EMPLOYEE', 'ACTIVE', '2020-01-01', NULL);

INSERT INTO role_assignments
    (id, user_profile_id, organization_id, role_id, scope_type, scope_id,
     status, valid_from, valid_to)
VALUES
    ('72000000-0000-0000-0000-000000000003',
     '72000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000101',
     '11000000-0000-0000-0000-000000000011',
     'ORGANIZATION', '00000000-0000-0000-0000-000000000101',
     'ACTIVE', '2020-01-01', NULL);

-- The system-E2E governance identity has both the independently assigned
-- platform authority required to define flags and an engagement-scoped
-- authority used to evaluate/version the canary for the client engagement.
INSERT INTO f07_platform_role_assignments
    (id, user_profile_id, role_id, scope_type, status, valid_from,
     assigned_by_subject)
VALUES
    ('72000000-0000-0000-0000-000000000004',
     '00000000-0000-0000-0000-000000000222',
     '11000000-0000-0000-0000-000000000090',
     'SYSTEM', 'ACTIVE', '2020-01-01', 'SYSTEM:E2E');

INSERT INTO greythr_connections
    (id, organization_id, display_name, status, adapter_mode,
     credential_reference, created_by_subject)
VALUES
    ('72000000-0000-0000-0000-000000000010',
     '00000000-0000-0000-0000-000000000101',
     'F07 recorded greytHR adapter', 'DISCOVERED', 'RECORDED_FIXTURE',
     'secret://f07-system/greythr', 'SYSTEM:E2E');

INSERT INTO greythr_recorded_pages
    (connection_id, page_number, response_mode, payload, source_updated_at)
VALUES
    ('72000000-0000-0000-0000-000000000010', 1, 'AVAILABLE',
     '{
       "employees":[{
         "providerRecordId":"employee-af-001-v1",
         "providerEmployeeId":"GHR-AF-001",
         "employeeNumber":"AF-001",
         "workEmail":"employee@arrowfoundry.example"
       }]
     }'::jsonb, '2026-07-29T09:00:00Z'),
    ('72000000-0000-0000-0000-000000000010', 2, 'AVAILABLE',
     '{
       "attendance":[{
         "providerRecordId":"attendance-af-001-2026-07-07",
         "providerEmployeeId":"GHR-AF-001",
         "workDate":"2026-07-07",
         "checkInAt":"2026-07-07T03:30:00Z",
         "checkOutAt":"2026-07-07T12:30:00Z"
       }],
       "leave":[{
         "providerRecordId":"leave-af-001-2026-07-08",
         "providerEmployeeId":"GHR-AF-001",
         "workDate":"2026-07-08",
         "leaveTypeCode":"CL",
         "units":0.5
       }]
     }'::jsonb, '2026-07-29T09:00:00Z');

INSERT INTO attendance_events
    (id, employee_id, event_type, occurred_at, work_date, source,
     idempotency_key, recorded_by_subject)
VALUES
    ('72000000-0000-0000-0000-000000000021',
     '00000000-0000-0000-0000-000000000801',
     'CHECK_IN', '2026-07-07T03:45:00Z', '2026-07-07',
     'INTERNAL_WEB', 'f07-greythr-conflict-in', 'SYSTEM:E2E'),
    ('72000000-0000-0000-0000-000000000022',
     '00000000-0000-0000-0000-000000000801',
     'CHECK_OUT', '2026-07-07T12:15:00Z', '2026-07-07',
     'INTERNAL_WEB', 'f07-greythr-conflict-out', 'SYSTEM:E2E');

INSERT INTO attendance_sessions
    (id, employee_id, work_date, check_in_event_id, check_out_event_id,
     check_in_at, check_out_at, net_minutes, status)
VALUES
    ('72000000-0000-0000-0000-000000000023',
     '00000000-0000-0000-0000-000000000801', '2026-07-07',
     '72000000-0000-0000-0000-000000000021',
     '72000000-0000-0000-0000-000000000022',
     '2026-07-07T03:45:00Z', '2026-07-07T12:15:00Z', 510, 'CLOSED');
