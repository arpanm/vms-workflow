-- F03 operator recovery: retain a dead-lettered commitment as immutable
-- evidence and record each authorized resend as a separately queued outbox row.
-- A replay never edits or reopens the original delivery attempt.

INSERT INTO permissions (id, code, description) VALUES
    ('10000000-0000-0000-0000-000000000027',
     'delivery.commitment.replay',
     'List and replay dead-lettered delivery commitment transport work')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT role.id, permission.id
FROM roles role
JOIN permissions permission ON permission.code = 'delivery.commitment.replay'
WHERE role.code IN ('ORG_ADMIN', 'ENGAGEMENT_ADMIN', 'INTEGRATION_ADMIN')
ON CONFLICT DO NOTHING;

CREATE TABLE commitment_outbox_replays (
    id UUID PRIMARY KEY,
    original_outbox_id UUID NOT NULL REFERENCES commitment_outbox(id),
    replay_outbox_id UUID NOT NULL UNIQUE REFERENCES commitment_outbox(id),
    idempotency_key VARCHAR(160) NOT NULL
        CHECK (btrim(idempotency_key) <> ''),
    command_checksum VARCHAR(64) NOT NULL
        CHECK (command_checksum ~ '^[0-9a-f]{64}$'),
    reason TEXT NOT NULL CHECK (btrim(reason) <> ''),
    actor_subject VARCHAR(255) NOT NULL CHECK (btrim(actor_subject) <> ''),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (original_outbox_id, idempotency_key)
);

CREATE INDEX idx_commitment_outbox_replays_original
    ON commitment_outbox_replays(original_outbox_id, created_at DESC, id);

CREATE TRIGGER commitment_outbox_replays_immutable
BEFORE UPDATE OR DELETE ON commitment_outbox_replays
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

ALTER TABLE commitment_outbox_replays OWNER TO vms_migration_owner;
ALTER FUNCTION reject_immutable_change() OWNER TO vms_migration_owner;
REVOKE ALL ON TABLE commitment_outbox_replays
FROM PUBLIC, vms_reporting, vms_job_worker, vms_migration_processor;
GRANT SELECT, INSERT ON commitment_outbox_replays TO vms_app_runtime;
GRANT SELECT ON commitment_outbox_replays TO vms_backup;
