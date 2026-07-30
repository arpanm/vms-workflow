-- Durable, idempotent request marker for immediate worker execution.  The job
-- remains the aggregate and its existing PostgreSQL lease/checkpoint/retry
-- fields remain the single execution authority.
ALTER TABLE migration_jobs
    ADD COLUMN async_requested_at TIMESTAMPTZ,
    ADD COLUMN async_requested_by VARCHAR(255),
    ADD COLUMN async_idempotency_key VARCHAR(255);

CREATE UNIQUE INDEX uq_migration_async_request_actor_key
    ON migration_jobs(async_requested_by, async_idempotency_key)
    WHERE async_idempotency_key IS NOT NULL;

CREATE INDEX idx_migration_jobs_async_ready
    ON migration_jobs(async_requested_at, updated_at, id)
    WHERE async_requested_at IS NOT NULL
      AND dead_lettered_at IS NULL;
