INSERT INTO user_profiles (id, identity_subject, email, display_name, status)
VALUES
    ('00000000-0000-0000-0000-000000000212', 'user-employee',
     'employee@arrowfoundry.example', 'Esha Employee', 'ACTIVE');

INSERT INTO memberships
    (id, user_profile_id, organization_id, role_code, status, valid_from, valid_to)
VALUES
    ('00000000-0000-0000-0000-000000000312',
     '00000000-0000-0000-0000-000000000212',
     '00000000-0000-0000-0000-000000000101',
     'EMPLOYEE', 'ACTIVE', '2020-01-01', NULL);

INSERT INTO role_assignments
    (id, user_profile_id, organization_id, role_id, scope_type, scope_id,
     status, valid_from, valid_to)
VALUES
    ('12000000-0000-0000-0000-000000000012',
     '00000000-0000-0000-0000-000000000212',
     '00000000-0000-0000-0000-000000000101',
     '11000000-0000-0000-0000-000000000011',
     'ORGANIZATION', '00000000-0000-0000-0000-000000000101',
     'ACTIVE', '2020-01-01', NULL),
    ('12000000-0000-0000-0000-000000000013',
     '00000000-0000-0000-0000-000000000201',
     '00000000-0000-0000-0000-000000000101',
     '11000000-0000-0000-0000-000000000001',
     'ORGANIZATION', '00000000-0000-0000-0000-000000000101',
     'ACTIVE', '2020-01-01', NULL);

INSERT INTO employees
    (id, organization_id, employee_number, work_email, user_profile_id,
     join_date, created_by_subject)
VALUES
    ('00000000-0000-0000-0000-000000000801',
     '00000000-0000-0000-0000-000000000101',
     'AF-001', 'employee@arrowfoundry.example',
     '00000000-0000-0000-0000-000000000212',
     '2026-01-01', 'test-fixture');

INSERT INTO employee_versions
    (id, employee_id, version, valid_from, first_name, last_name, display_name,
     designation, employment_status, activation_status, recorded_by_subject)
VALUES
    ('00000000-0000-0000-0000-000000000811',
     '00000000-0000-0000-0000-000000000801',
     1, '2026-01-01', 'Esha', 'Employee', 'Esha Employee',
     'Engineer', 'ACTIVE', 'ENABLED', 'test-fixture');

INSERT INTO attendance_source_mode_assignments
    (id, employee_id, mode, authoritative_source, valid_from, created_by_subject)
VALUES
    ('00000000-0000-0000-0000-000000000821',
     '00000000-0000-0000-0000-000000000801',
     'INTERNAL_AUTHORITATIVE', 'INTERNAL', '2026-01-01', 'test-fixture');

INSERT INTO employee_project_allocations
    (id, employee_id, engagement_id, project_id, valid_from, valid_to,
     allocation_percent, role_on_project, status, created_by_subject)
VALUES
    ('00000000-0000-0000-0000-000000000831',
     '00000000-0000-0000-0000-000000000801',
     '00000000-0000-0000-0000-000000000401',
     '00000000-0000-0000-0000-000000000501',
     '2026-01-01', NULL, 50, 'Engineer', 'ACTIVE', 'test-fixture');

INSERT INTO working_calendar_versions
    (id, organization_id, name, timezone, version, valid_from,
     expected_full_minutes, expected_half_minutes)
VALUES
    ('00000000-0000-0000-0000-000000000901',
     '00000000-0000-0000-0000-000000000101',
     'ArrowFoundry Standard', 'Asia/Kolkata', 1, '2026-01-01', 540, 270);

INSERT INTO working_calendar_weekdays
    (calendar_version_id, iso_weekday, classification, expected_minutes)
VALUES
    ('00000000-0000-0000-0000-000000000901', 1, 'WORKING', 540),
    ('00000000-0000-0000-0000-000000000901', 2, 'WORKING', 540),
    ('00000000-0000-0000-0000-000000000901', 3, 'WORKING', 540),
    ('00000000-0000-0000-0000-000000000901', 4, 'WORKING', 540),
    ('00000000-0000-0000-0000-000000000901', 5, 'WORKING', 540),
    ('00000000-0000-0000-0000-000000000901', 6, 'WEEKLY_OFF', 0),
    ('00000000-0000-0000-0000-000000000901', 7, 'WEEKLY_OFF', 0);

INSERT INTO calendar_holidays
    (id, calendar_version_id, holiday_date, name, classification, expected_minutes)
VALUES
    ('00000000-0000-0000-0000-000000000902',
     '00000000-0000-0000-0000-000000000901',
     '2026-07-06', 'Synthetic Holiday', 'HOLIDAY', 0);

INSERT INTO employee_calendar_assignments
    (id, employee_id, calendar_version_id, valid_from)
VALUES
    ('00000000-0000-0000-0000-000000000903',
     '00000000-0000-0000-0000-000000000801',
     '00000000-0000-0000-0000-000000000901', '2026-01-01');

-- Historical finalized roster fixture. It deliberately predates shift-policy
-- assignment rollout so readiness tests can still prove the missing-shift
-- blocker while attendance snapshot regression tests retain immutable June
-- roster evidence.
INSERT INTO workforce_roster_snapshot_versions
    (id, engagement_month_id, version, checksum, employee_count,
     employee_day_count, finalized_by_subject, reason)
VALUES
    ('00000000-0000-0000-0000-000000000905',
     '00000000-0000-0000-0000-000000000601',
     1,
     '0000000000000000000000000000000000000000000000000000000000000000',
     1, 30, 'test-fixture', 'Historical roster before shift policy rollout');

INSERT INTO workforce_roster_snapshot_days
    (snapshot_id, employee_id, work_date, project_allocation_id, project_id,
     allocation_percent, timezone, expected_classification, expected_minutes)
SELECT
    '00000000-0000-0000-0000-000000000905',
    '00000000-0000-0000-0000-000000000801',
    day.work_date::date,
    '00000000-0000-0000-0000-000000000831',
    '00000000-0000-0000-0000-000000000501',
    50,
    'Asia/Kolkata',
    CASE
      WHEN EXTRACT(ISODOW FROM day.work_date::date) IN (6, 7)
        THEN 'WEEKLY_OFF'
      ELSE 'WORKING'
    END,
    CASE
      WHEN EXTRACT(ISODOW FROM day.work_date::date) IN (6, 7) THEN 0
      ELSE 540
    END
FROM generate_series(
    DATE '2026-06-01',
    DATE '2026-06-30',
    INTERVAL '1 day'
) AS day(work_date);

INSERT INTO employee_date_overrides
    (id, employee_id, override_date, classification, expected_minutes,
     reason, created_by_subject)
VALUES
    ('00000000-0000-0000-0000-000000000904',
     '00000000-0000-0000-0000-000000000801',
     '2026-07-04', 'WORKING', 540, 'Approved working Saturday', 'test-fixture');

INSERT INTO leave_types
    (id, organization_id, code, name, paid, balance_tracked, minimum_increment)
VALUES
    ('00000000-0000-0000-0000-000000000921',
     '00000000-0000-0000-0000-000000000101',
     'CL', 'Casual Leave', TRUE, TRUE, 0.5),
    ('00000000-0000-0000-0000-000000000922',
     '00000000-0000-0000-0000-000000000101',
     'LWP', 'Leave Without Pay', FALSE, FALSE, 0.5);

INSERT INTO leave_balance_ledger
    (id, employee_id, leave_type_id, entry_type, quantity, effective_date,
     idempotency_key, reason, recorded_by_subject)
VALUES
    ('00000000-0000-0000-0000-000000000923',
     '00000000-0000-0000-0000-000000000801',
     '00000000-0000-0000-0000-000000000921',
     'OPENING_BALANCE', 0.5, '2026-01-01', 'opening-cl-2026',
     'Opening test balance', 'test-fixture');
