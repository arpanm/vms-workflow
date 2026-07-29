ALTER TABLE commitment_outbox
    DROP CONSTRAINT commitment_outbox_status_check;

ALTER TABLE commitment_outbox
    ADD CONSTRAINT commitment_outbox_status_check CHECK (
        status IN ('PENDING', 'SENDING', 'SENT', 'RETRY', 'DEAD_LETTER')
    ),
    ADD COLUMN lease_owner VARCHAR(160),
    ADD COLUMN lease_expires_at TIMESTAMPTZ,
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0
        CHECK (attempt_count >= 0),
    ADD COLUMN next_attempt_at TIMESTAMPTZ,
    ADD COLUMN provider_message_id VARCHAR(255),
    ADD COLUMN provider_thread_id VARCHAR(255),
    ADD COLUMN sent_at TIMESTAMPTZ,
    ADD COLUMN dead_lettered_at TIMESTAMPTZ,
    ADD COLUMN last_error_code VARCHAR(64),
    ADD CONSTRAINT ck_commitment_outbox_lease CHECK (
        (lease_owner IS NULL AND lease_expires_at IS NULL)
        OR (lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)
    );

CREATE INDEX idx_commitment_outbox_dispatch
    ON commitment_outbox(next_attempt_at, created_at, id)
    WHERE status IN ('PENDING', 'RETRY', 'SENDING');

DROP TRIGGER commitment_outbox_immutable ON commitment_outbox;

CREATE OR REPLACE FUNCTION delivery_commitment_outbox_content_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id <> OLD.id
       OR NEW.plan_version_id <> OLD.plan_version_id
       OR NEW.baseline_id <> OLD.baseline_id
       OR NEW.message_type <> OLD.message_type
       OR NEW.idempotency_key <> OLD.idempotency_key
       OR NEW.recipient_snapshot <> OLD.recipient_snapshot
       OR NEW.subject_text <> OLD.subject_text
       OR NEW.plain_text <> OLD.plain_text
       OR NEW.html_text <> OLD.html_text
       OR NEW.archive_reference <> OLD.archive_reference
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'Commitment content is immutable'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER commitment_outbox_content_gate
BEFORE UPDATE ON commitment_outbox
FOR EACH ROW EXECUTE FUNCTION delivery_commitment_outbox_content_guard();

CREATE TRIGGER commitment_outbox_delete_gate
BEFORE DELETE ON commitment_outbox
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

ALTER FUNCTION delivery_commitment_outbox_content_guard()
    OWNER TO vms_migration_owner;
