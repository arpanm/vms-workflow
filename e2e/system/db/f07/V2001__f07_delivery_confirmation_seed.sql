-- The partial-certification journey must create a real carry-forward lineage
-- into the next engagement month. This is a calendar/scope fixture only; all
-- plan, submission, certification and confirmation facts are created through
-- the secured Java APIs by Playwright.
INSERT INTO engagement_months
    (id, engagement_id, month_start_date, state, risk_status)
VALUES
    ('73000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000401',
     '2026-08-01', 'PLANNING', 'ON_TRACK');
