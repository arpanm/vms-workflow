-- Reopen requests are immutable; their effective status is derived from the
-- append-only decision table. The original partial index therefore treated
-- every approved/rejected request as REQUESTED forever and blocked all later
-- governed corrections for the month.
DROP INDEX IF EXISTS uq_pending_month_reopen;

CREATE OR REPLACE FUNCTION enforce_single_effective_pending_reopen()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM public.month_reopen_requests request
        LEFT JOIN public.month_reopen_decisions decision
          ON decision.reopen_request_id = request.id
        WHERE request.engagement_month_id = NEW.engagement_month_id
          AND decision.id IS NULL
    ) THEN
        RAISE EXCEPTION
            'duplicate key value violates unique constraint "uq_pending_month_reopen"'
            USING ERRCODE = '23505',
                  SCHEMA = 'public',
                  TABLE = 'month_reopen_requests',
                  CONSTRAINT = 'uq_pending_month_reopen',
                  DETAIL = format(
                    'Key (engagement_month_id)=(%s) already has an effective pending reopen request.',
                    NEW.engagement_month_id);
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER f04_single_effective_pending_reopen
BEFORE INSERT ON month_reopen_requests
FOR EACH ROW EXECUTE FUNCTION enforce_single_effective_pending_reopen();
