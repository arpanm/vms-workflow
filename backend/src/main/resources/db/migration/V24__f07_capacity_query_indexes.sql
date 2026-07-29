-- F07-T056: indexes used by the scoped workforce/search/reporting capacity paths.
-- Keep these additive so a rollback can revert the application without losing data.

CREATE INDEX idx_employee_versions_current_name_search
    ON employee_versions (lower(display_name) text_pattern_ops, employee_id)
    WHERE valid_to IS NULL;

CREATE INDEX idx_employee_versions_effective_employee
    ON employee_versions (employee_id, valid_from, valid_to);

CREATE INDEX idx_attendance_source_effective_employee
    ON attendance_source_mode_assignments (employee_id, valid_from, valid_to);

CREATE INDEX idx_attendance_days_current_date_employee
    ON attendance_days (work_date, employee_id)
    INCLUDE (net_minutes, final_status)
    WHERE is_current;

CREATE INDEX idx_f05_report_exports_subject_scope_page
    ON f05_report_exports
        (requested_by_subject, engagement_id, requested_at DESC, id DESC);
