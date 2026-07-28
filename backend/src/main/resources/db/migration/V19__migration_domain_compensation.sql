-- F06 domain compensation is append-only even when the owned domain effect
-- must be removed or restored so normal APIs no longer observe the rollback.

ALTER TABLE migration_domain_provenance
    ADD COLUMN effect_sequence INTEGER NOT NULL DEFAULT 1
        CHECK (effect_sequence > 0),
    ADD COLUMN effect_kind VARCHAR(16) NOT NULL DEFAULT 'INSERT'
        CHECK (effect_kind IN ('INSERT', 'UPDATE')),
    ADD COLUMN before_state JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN compensated_at TIMESTAMPTZ;

-- One row may legitimately apply multiple ordered effects to the same domain
-- record (for example, insert a plan and then set its current version). The
-- effect sequence, not the record identity, is the idempotent row-local key.
ALTER TABLE migration_domain_provenance
    DROP CONSTRAINT
        migration_domain_provenance_job_id_row_id_domain_table_doma_key;
ALTER TABLE migration_domain_provenance
    ADD CONSTRAINT uq_migration_domain_effect_sequence
        UNIQUE (job_id, row_id, effect_sequence);

CREATE TABLE migration_domain_compensations (
    id UUID PRIMARY KEY,
    rollback_action_id UUID NOT NULL REFERENCES migration_rollback_actions(id),
    provenance_id UUID NOT NULL UNIQUE
        REFERENCES migration_domain_provenance(id),
    domain_table VARCHAR(100) NOT NULL,
    domain_record_id UUID NOT NULL,
    compensation_kind VARCHAR(24) NOT NULL CHECK (
        compensation_kind IN (
            'DELETE_INSERTED', 'DEACTIVATE_INSERTED', 'RESTORE_PREVIOUS')),
    compensated_by_subject VARCHAR(255) NOT NULL,
    compensated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_migration_domain_compensations_action
    ON migration_domain_compensations(rollback_action_id, compensated_at);

ALTER TABLE migration_domain_provenance
    ADD CONSTRAINT fk_migration_domain_provenance_compensation
    FOREIGN KEY (compensation_record_id)
    REFERENCES migration_domain_compensations(id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TRIGGER migration_domain_compensations_immutable
BEFORE UPDATE OR DELETE ON migration_domain_compensations
FOR EACH ROW EXECUTE FUNCTION f06_reject_mutation();

-- Domain append-only guards remain strict for every ordinary caller. The
-- migration rollback transaction may delete only a record with active F06
-- provenance, and only while an explicit rollback action is set locally.
CREATE OR REPLACE FUNCTION reject_immutable_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    compensation_action TEXT;
    authorized BOOLEAN := FALSE;
BEGIN
    compensation_action :=
        NULLIF(current_setting('vms.migration_compensation', TRUE), '');
    IF TG_OP = 'DELETE' AND compensation_action IS NOT NULL THEN
        SELECT EXISTS (
            SELECT 1
            FROM migration_domain_provenance provenance
            WHERE provenance.active
              AND provenance.domain_table = TG_TABLE_NAME
              AND provenance.domain_record_id = OLD.id
        ) INTO authorized;
        IF authorized THEN
            RETURN OLD;
        END IF;
    END IF;
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME USING ERRCODE = '55000';
END;
$$;

-- F05 uses a separate append-only guard for invoice versions. Apply the same
-- narrow provenance gate; no unprovenanced finance history can be removed.
CREATE OR REPLACE FUNCTION f05_reject_immutable_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    compensation_action TEXT;
    authorized BOOLEAN := FALSE;
BEGIN
    compensation_action :=
        NULLIF(current_setting('vms.migration_compensation', TRUE), '');
    IF TG_OP = 'DELETE' AND compensation_action IS NOT NULL THEN
        SELECT EXISTS (
            SELECT 1
            FROM migration_domain_provenance provenance
            WHERE provenance.active
              AND provenance.domain_table = TG_TABLE_NAME
              AND provenance.domain_record_id = OLD.id
        ) INTO authorized;
        IF authorized THEN
            RETURN OLD;
        END IF;
    END IF;
    RAISE EXCEPTION 'F05 historical record is immutable'
        USING ERRCODE = '23514';
END;
$$;
