CREATE TABLE leave_request_days (
    leave_request_id UUID NOT NULL REFERENCES leave_requests(id),
    leave_date DATE NOT NULL,
    paid_units NUMERIC(4,2) NOT NULL CHECK (paid_units >= 0),
    lwp_units NUMERIC(4,2) NOT NULL CHECK (lwp_units >= 0),
    PRIMARY KEY (leave_request_id, leave_date),
    CONSTRAINT ck_leave_request_day_units CHECK (
        paid_units + lwp_units > 0
        AND paid_units + lwp_units <= 1
    )
);

-- Preserve the aggregate split of requests created before this migration while
-- assigning each unit to one date only. Paid units are assigned first, followed
-- by LWP units, in request-date order.
WITH eligible_dates AS (
    SELECT request.id AS leave_request_id,
           request.requested_units,
           request.paid_units,
           series.leave_date::date AS leave_date,
           ROW_NUMBER() OVER (
               PARTITION BY request.id ORDER BY series.leave_date
           ) - 1 AS day_index
    FROM leave_requests request
    CROSS JOIN LATERAL generate_series(
        request.start_date,
        request.end_date,
        INTERVAL '1 day'
    ) AS series(leave_date)
    WHERE COALESCE(
        (
            SELECT override.expected_minutes
            FROM employee_date_overrides override
            WHERE override.employee_id = request.employee_id
              AND override.override_date = series.leave_date::date
        ),
        (
            SELECT COALESCE(holiday.expected_minutes, weekday.expected_minutes)
            FROM employee_calendar_assignments assignment
            JOIN working_calendar_weekdays weekday
              ON weekday.calendar_version_id = assignment.calendar_version_id
             AND weekday.iso_weekday =
                 EXTRACT(ISODOW FROM series.leave_date::date)
            LEFT JOIN calendar_holidays holiday
              ON holiday.calendar_version_id = assignment.calendar_version_id
             AND holiday.holiday_date = series.leave_date::date
            WHERE assignment.employee_id = request.employee_id
              AND assignment.valid_from <= series.leave_date::date
              AND (
                  assignment.valid_to IS NULL
                  OR assignment.valid_to >= series.leave_date::date
              )
        ),
        540
    ) > 0
),
allocation AS (
    SELECT eligible.leave_request_id,
           eligible.leave_date,
           LEAST(
               1::numeric,
               eligible.requested_units - eligible.day_index
           ) AS day_units,
           eligible.paid_units - eligible.day_index AS paid_remaining
    FROM eligible_dates eligible
    WHERE eligible.requested_units > eligible.day_index
)
INSERT INTO leave_request_days (leave_request_id, leave_date, paid_units, lwp_units)
SELECT allocation.leave_request_id,
       allocation.leave_date,
       LEAST(allocation.day_units, GREATEST(allocation.paid_remaining, 0)),
       allocation.day_units
           - LEAST(allocation.day_units, GREATEST(allocation.paid_remaining, 0))
FROM allocation;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM leave_requests request
        LEFT JOIN leave_request_days day ON day.leave_request_id = request.id
        GROUP BY request.id, request.requested_units
        HAVING COALESCE(SUM(day.paid_units + day.lwp_units), 0)
            <> request.requested_units
    ) THEN
        RAISE EXCEPTION
            'Existing leave request units exceed eligible working dates';
    END IF;
END;
$$;

CREATE TRIGGER leave_request_days_immutable
BEFORE UPDATE OR DELETE ON leave_request_days
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
