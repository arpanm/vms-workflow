-- The delivery-commitment dispatcher is a dedicated non-web process using the
-- existing queue-only capability role. Grant only its SQL call graph: read and
-- transport-state transitions on the outbox, plus append-only attempts.
REVOKE ALL ON TABLE
    commitment_outbox,
    commitment_outbox_attempts
FROM vms_job_worker;

GRANT SELECT ON commitment_outbox TO vms_job_worker;

GRANT UPDATE (
    status,
    lease_owner,
    lease_expires_at,
    attempt_count,
    next_attempt_at,
    provider_message_id,
    provider_thread_id,
    sent_at,
    dead_lettered_at,
    last_error_code
) ON commitment_outbox TO vms_job_worker;

GRANT INSERT ON commitment_outbox_attempts TO vms_job_worker;

ALTER FUNCTION delivery_commitment_outbox_content_guard()
    SET search_path = pg_catalog, public;
REVOKE ALL ON FUNCTION delivery_commitment_outbox_content_guard()
    FROM PUBLIC;
GRANT EXECUTE ON FUNCTION delivery_commitment_outbox_content_guard()
    TO vms_app_runtime;
