CREATE TABLE linear_reconciliation_commands (
    id UUID PRIMARY KEY,
    connection_id UUID NOT NULL REFERENCES linear_connections(id),
    sync_job_id UUID NOT NULL UNIQUE REFERENCES linear_sync_jobs(id),
    idempotency_key VARCHAR(160) NOT NULL
        CHECK (btrim(idempotency_key) <> ''),
    command_checksum VARCHAR(64) NOT NULL
        CHECK (command_checksum ~ '^[0-9a-f]{64}$'),
    outcome VARCHAR(16) NOT NULL
        CHECK (outcome IN ('AVAILABLE', 'UNAVAILABLE')),
    error_code VARCHAR(64),
    recorded_connection_status VARCHAR(24) NOT NULL
        CHECK (recorded_connection_status IN ('CONNECTED', 'ACTION_REQUIRED')),
    recorded_stale_issue_count INTEGER NOT NULL
        CHECK (recorded_stale_issue_count >= 0),
    reason TEXT NOT NULL CHECK (btrim(reason) <> ''),
    actor_subject VARCHAR(255) NOT NULL
        CHECK (btrim(actor_subject) <> ''),
    correlation_id UUID NOT NULL,
    causation_id UUID NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (connection_id, idempotency_key),
    CHECK (
        (outcome = 'AVAILABLE' AND error_code IS NULL)
        OR (outcome = 'UNAVAILABLE' AND error_code IS NOT NULL)
    )
);

CREATE OR REPLACE FUNCTION enforce_linear_reconciliation_terminal()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
DECLARE
    job_connection UUID;
    job_status VARCHAR(24);
    job_error VARCHAR(64);
BEGIN
    SELECT connection_id, status, last_error_code
      INTO job_connection, job_status, job_error
    FROM public.linear_sync_jobs
    WHERE id = NEW.sync_job_id;
    IF job_connection IS NULL OR job_connection <> NEW.connection_id THEN
        RAISE EXCEPTION 'Linear reconciliation job scope mismatch';
    END IF;
    IF NEW.outcome = 'AVAILABLE'
       AND (job_status <> 'SUCCEEDED' OR job_error IS NOT NULL) THEN
        RAISE EXCEPTION 'Available reconciliation requires terminal success';
    END IF;
    IF NEW.outcome = 'AVAILABLE'
       AND NEW.recorded_connection_status <> 'CONNECTED' THEN
        RAISE EXCEPTION
            'Available reconciliation requires a connected response snapshot';
    END IF;
    IF NEW.outcome = 'UNAVAILABLE'
       AND (job_status <> 'FAILED' OR job_error IS DISTINCT FROM NEW.error_code) THEN
        RAISE EXCEPTION 'Unavailable reconciliation requires matching terminal failure';
    END IF;
    IF NEW.outcome = 'UNAVAILABLE'
       AND NEW.recorded_connection_status <> 'ACTION_REQUIRED' THEN
        RAISE EXCEPTION
            'Unavailable reconciliation requires an action-required response snapshot';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION enforce_linear_reconciliation_job_immutable()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM public.linear_reconciliation_commands command
        WHERE command.sync_job_id = OLD.id
    ) THEN
        RAISE EXCEPTION
            'A terminal Linear reconciliation job is immutable';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER linear_reconciliation_commands_terminal
BEFORE INSERT ON linear_reconciliation_commands
FOR EACH ROW EXECUTE FUNCTION enforce_linear_reconciliation_terminal();

CREATE TRIGGER linear_reconciliation_commands_immutable
BEFORE UPDATE OR DELETE ON linear_reconciliation_commands
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

CREATE TRIGGER linear_reconciliation_jobs_immutable
BEFORE UPDATE OR DELETE ON linear_sync_jobs
FOR EACH ROW EXECUTE FUNCTION enforce_linear_reconciliation_job_immutable();

ALTER TABLE linear_reconciliation_commands OWNER TO vms_migration_owner;
ALTER FUNCTION enforce_linear_reconciliation_terminal()
    OWNER TO vms_migration_owner;
ALTER FUNCTION enforce_linear_reconciliation_job_immutable()
    OWNER TO vms_migration_owner;

REVOKE ALL ON TABLE linear_reconciliation_commands
FROM PUBLIC, vms_reporting, vms_job_worker, vms_migration_processor;
GRANT SELECT, INSERT ON TABLE linear_reconciliation_commands
TO vms_app_runtime;
GRANT SELECT ON TABLE linear_reconciliation_commands TO vms_backup;
REVOKE ALL ON FUNCTION enforce_linear_reconciliation_terminal() FROM PUBLIC;
REVOKE ALL ON FUNCTION enforce_linear_reconciliation_job_immutable()
FROM PUBLIC;
GRANT EXECUTE ON FUNCTION enforce_linear_reconciliation_terminal(),
    enforce_linear_reconciliation_job_immutable()
TO vms_app_runtime, vms_migration_owner;
