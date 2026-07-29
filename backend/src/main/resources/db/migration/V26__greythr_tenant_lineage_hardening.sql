-- greytHR hardening is forward-only. Existing V25 rows are backfilled before
-- tenant-consistency constraints become mandatory; historical rows are never
-- deleted or rewritten except to attach deterministic lineage/tenant keys.

ALTER TABLE organizations
    ADD CONSTRAINT uq_organizations_id_tenant UNIQUE (id);

ALTER TABLE employees
    ADD CONSTRAINT uq_employees_id_organization
        UNIQUE (id, organization_id);

ALTER TABLE integration_capability_certifications
    ADD CONSTRAINT uq_capability_certification_organization
        UNIQUE (id, organization_id);

ALTER TABLE attendance_source_mode_assignments
    ADD CONSTRAINT uq_source_assignment_employee_certification
        UNIQUE (id, employee_id, capability_certification_id);

ALTER TABLE greythr_connections
    ADD CONSTRAINT uq_greythr_connection_organization
        UNIQUE (id, organization_id),
    ADD CONSTRAINT fk_greythr_connection_certification_tenant
        FOREIGN KEY (capability_certification_id, organization_id)
        REFERENCES integration_capability_certifications(id, organization_id);

ALTER TABLE greythr_recorded_pages
    ADD CONSTRAINT ck_greythr_recorded_page_limit
        CHECK (page_number <= 100),
    ADD CONSTRAINT ck_greythr_recorded_page_payload_limit
        CHECK (pg_column_size(payload) <= 1048576),
    ADD CONSTRAINT ck_greythr_recorded_page_no_commercial_data
        CHECK (f07_assert_no_commercial_fields(payload));

ALTER TABLE greythr_sync_runs
    ADD CONSTRAINT uq_greythr_sync_connection_tenant
        UNIQUE (id, connection_id, organization_id),
    ADD CONSTRAINT fk_greythr_sync_connection_tenant
        FOREIGN KEY (connection_id, organization_id)
        REFERENCES greythr_connections(id, organization_id),
    ADD CONSTRAINT ck_greythr_sync_idempotency_canonical
        CHECK (
            idempotency_key
                ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{0,254}$'
        ),
    ADD CONSTRAINT ck_greythr_sync_range_bounded
        CHECK (date_to <= date_from + 366),
    ADD CONSTRAINT ck_greythr_sync_completion_state
        CHECK (
            (status = 'RUNNING'
                AND completed_at IS NULL
                AND error_code IS NULL)
            OR
            (status = 'COMPLETED'
                AND completed_at IS NOT NULL
                AND error_code IS NULL)
            OR
            (status IN ('DEGRADED', 'FAILED')
                AND completed_at IS NOT NULL
                AND error_code IS NOT NULL
                AND btrim(error_code) <> '')
        );

ALTER TABLE greythr_imported_facts
    ADD COLUMN organization_id UUID;

-- V25 already protects imported facts with an append-only trigger. Disable only
-- that named trigger for the bounded tenant/lineage backfill in this migration;
-- PostgreSQL transactional DDL restores it automatically if any statement fails.
ALTER TABLE greythr_imported_facts
    DISABLE TRIGGER greythr_imported_facts_immutable;

UPDATE greythr_imported_facts fact
SET organization_id = connection.organization_id
FROM greythr_connections connection
WHERE connection.id = fact.connection_id;

ALTER TABLE greythr_imported_facts
    ALTER COLUMN organization_id SET NOT NULL,
    ADD CONSTRAINT uq_greythr_fact_connection_tenant
        UNIQUE (id, connection_id, organization_id),
    ADD CONSTRAINT uq_greythr_fact_reconciliation_identity
        UNIQUE (
            id, connection_id, organization_id, employee_id, work_date
        ),
    ADD CONSTRAINT uq_greythr_fact_supersession_identity
        UNIQUE (
            id, connection_id, organization_id, fact_kind, provider_record_id
        ),
    ADD CONSTRAINT fk_greythr_fact_connection_tenant
        FOREIGN KEY (connection_id, organization_id)
        REFERENCES greythr_connections(id, organization_id),
    ADD CONSTRAINT fk_greythr_fact_employee_tenant
        FOREIGN KEY (employee_id, organization_id)
        REFERENCES employees(id, organization_id),
    ADD CONSTRAINT fk_greythr_fact_sync_connection_tenant
        FOREIGN KEY (sync_run_id, connection_id, organization_id)
        REFERENCES greythr_sync_runs(id, connection_id, organization_id),
    ADD CONSTRAINT ck_greythr_fact_work_date
        CHECK (
            (fact_kind = 'EMPLOYEE' AND work_date IS NULL)
            OR (fact_kind IN ('ATTENDANCE', 'LEAVE') AND work_date IS NOT NULL)
        ),
    ADD CONSTRAINT ck_greythr_fact_not_self_superseding
        CHECK (supersedes_id IS NULL OR supersedes_id <> id),
    ADD CONSTRAINT ck_greythr_fact_identifiers_nonblank
        CHECK (
            btrim(provider_employee_id) <> ''
            AND btrim(provider_record_id) <> ''
        ),
    ADD CONSTRAINT ck_greythr_fact_payload_limit
        CHECK (pg_column_size(payload) <= 1048576),
    ADD CONSTRAINT ck_greythr_fact_no_commercial_data
        CHECK (f07_assert_no_commercial_fields(payload));

-- Attach a deterministic chain to V25 corrections that predate this migration.
WITH ordered AS (
    SELECT id,
           lag(id) OVER (
               PARTITION BY connection_id, fact_kind, provider_record_id
               ORDER BY source_updated_at, recorded_at, id
           ) AS prior_id
    FROM greythr_imported_facts
)
UPDATE greythr_imported_facts fact
SET supersedes_id = ordered.prior_id
FROM ordered
WHERE ordered.id = fact.id
  AND fact.supersedes_id IS NULL
  AND ordered.prior_id IS NOT NULL;

ALTER TABLE greythr_imported_facts
    ENABLE TRIGGER greythr_imported_facts_immutable;

ALTER TABLE greythr_imported_facts
    ADD CONSTRAINT fk_greythr_fact_supersedes_same_identity
        FOREIGN KEY (
            supersedes_id, connection_id, organization_id, fact_kind,
            provider_record_id
        )
        REFERENCES greythr_imported_facts(
            id, connection_id, organization_id, fact_kind, provider_record_id
        );

ALTER TABLE greythr_reconciliation_items
    ADD COLUMN organization_id UUID;

UPDATE greythr_reconciliation_items item
SET organization_id = connection.organization_id
FROM greythr_connections connection
WHERE connection.id = item.connection_id;

ALTER TABLE greythr_reconciliation_items
    ALTER COLUMN organization_id SET NOT NULL,
    ADD CONSTRAINT fk_greythr_reconciliation_connection_tenant
        FOREIGN KEY (connection_id, organization_id)
        REFERENCES greythr_connections(id, organization_id),
    ADD CONSTRAINT fk_greythr_reconciliation_employee_tenant
        FOREIGN KEY (employee_id, organization_id)
        REFERENCES employees(id, organization_id),
    ADD CONSTRAINT fk_greythr_reconciliation_sync_tenant
        FOREIGN KEY (sync_run_id, connection_id, organization_id)
        REFERENCES greythr_sync_runs(id, connection_id, organization_id),
    ADD CONSTRAINT fk_greythr_reconciliation_fact_identity
        FOREIGN KEY (
            provider_fact_id, connection_id, organization_id, employee_id,
            work_date
        )
        REFERENCES greythr_imported_facts(
            id, connection_id, organization_id, employee_id, work_date
        ),
    ADD CONSTRAINT ck_greythr_reconciliation_decision
        CHECK (
            (status = 'PENDING'
                AND decision_reason IS NULL
                AND decided_at IS NULL
                AND decided_by_subject IS NULL)
            OR
            (status IN ('USE_GREYTHR', 'KEEP_INTERNAL')
                AND decision_reason IS NOT NULL
                AND btrim(decision_reason) <> ''
                AND decided_at IS NOT NULL
                AND decided_by_subject IS NOT NULL
                AND btrim(decided_by_subject) <> '')
        ),
    ADD CONSTRAINT ck_greythr_reconciliation_reason_limit
        CHECK (
            decision_reason IS NULL OR length(decision_reason) <= 4000
        );

ALTER TABLE greythr_cutovers
    ADD COLUMN organization_id UUID;

UPDATE greythr_cutovers cutover
SET organization_id = connection.organization_id
FROM greythr_connections connection
WHERE connection.id = cutover.connection_id;

ALTER TABLE greythr_cutovers
    ALTER COLUMN organization_id SET NOT NULL,
    ADD CONSTRAINT fk_greythr_cutover_connection_tenant
        FOREIGN KEY (connection_id, organization_id)
        REFERENCES greythr_connections(id, organization_id),
    ADD CONSTRAINT fk_greythr_cutover_employee_tenant
        FOREIGN KEY (employee_id, organization_id)
        REFERENCES employees(id, organization_id),
    ADD CONSTRAINT fk_greythr_cutover_certification_tenant
        FOREIGN KEY (capability_certification_id, organization_id)
        REFERENCES integration_capability_certifications(id, organization_id),
    ADD CONSTRAINT fk_greythr_cutover_source_identity
        FOREIGN KEY (
            source_assignment_id, employee_id, capability_certification_id
        )
        REFERENCES attendance_source_mode_assignments(
            id, employee_id, capability_certification_id
        ),
    ADD CONSTRAINT ck_greythr_cutover_reason_nonblank
        CHECK (btrim(reason) <> ''),
    ADD CONSTRAINT ck_greythr_cutover_actor_nonblank
        CHECK (btrim(created_by_subject) <> ''),
    ADD CONSTRAINT ck_greythr_cutover_reason_limit
        CHECK (length(reason) <= 4000);

CREATE TABLE greythr_certification_evidence (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    connection_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    certification_id UUID NOT NULL,
    certification_status VARCHAR(24) NOT NULL,
    capability_manifest JSONB NOT NULL,
    certified_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_greythr_certification_evidence
        UNIQUE (connection_id, certification_id),
    CONSTRAINT fk_greythr_certification_evidence_connection
        FOREIGN KEY (connection_id, organization_id)
        REFERENCES greythr_connections(id, organization_id),
    CONSTRAINT fk_greythr_certification_evidence_certification
        FOREIGN KEY (certification_id, organization_id)
        REFERENCES integration_capability_certifications(id, organization_id),
    CONSTRAINT ck_greythr_certification_evidence_status
        CHECK (certification_status = 'CERTIFIED')
);

CREATE TABLE greythr_certification_upgrade_audit (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    connection_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    certification_id UUID,
    certification_status VARCHAR(24),
    prior_connection_status VARCHAR(24) NOT NULL,
    resulting_connection_status VARCHAR(24) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_greythr_upgrade_audit_connection
        FOREIGN KEY (connection_id, organization_id)
        REFERENCES greythr_connections(id, organization_id),
    CONSTRAINT ck_greythr_upgrade_audit_reason
        CHECK (btrim(reason_code) <> '')
);

-- V25 allowed an ACTIVE connection to point at a DRAFT or REVOKED capability
-- record. Preserve an immutable audit and fail the connection closed instead of
-- aborting the whole V26 upgrade or treating the invalid record as evidence.
INSERT INTO greythr_certification_upgrade_audit(
    connection_id, organization_id, certification_id,
    certification_status, prior_connection_status,
    resulting_connection_status, reason_code
)
SELECT connection.id, connection.organization_id, certification.id,
       certification.status, connection.status, 'DISCOVERED',
       'CAPABILITY_CERTIFICATION_NOT_CERTIFIED'
FROM greythr_connections connection
JOIN integration_capability_certifications certification
  ON certification.id = connection.capability_certification_id
WHERE certification.status <> 'CERTIFIED';

UPDATE greythr_connections connection
SET status = 'DISCOVERED',
    capability_certification_id = NULL,
    last_error_code = 'CAPABILITY_CERTIFICATION_NOT_CERTIFIED'
FROM integration_capability_certifications certification
WHERE certification.id = connection.capability_certification_id
  AND certification.status <> 'CERTIFIED';

INSERT INTO greythr_certification_evidence(
    connection_id, organization_id, certification_id, certification_status,
    capability_manifest, certified_at
)
SELECT connection.id, connection.organization_id, certification.id,
       certification.status, certification.capability_manifest,
       certification.certified_at
FROM greythr_connections connection
JOIN integration_capability_certifications certification
  ON certification.id = connection.capability_certification_id
WHERE certification.status = 'CERTIFIED';

CREATE FUNCTION greythr_capture_certification_evidence()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF NEW.capability_certification_id IS NOT NULL
       AND NEW.capability_certification_id
           IS DISTINCT FROM OLD.capability_certification_id THEN
        INSERT INTO public.greythr_certification_evidence(
            connection_id, organization_id, certification_id,
            certification_status, capability_manifest, certified_at
        )
        SELECT NEW.id, NEW.organization_id, certification.id,
               certification.status, certification.capability_manifest,
               certification.certified_at
        FROM public.integration_capability_certifications certification
        WHERE certification.id = NEW.capability_certification_id
          AND certification.organization_id = NEW.organization_id
          AND certification.status = 'CERTIFIED';
        IF NOT FOUND THEN
            RAISE EXCEPTION
                'greytHR certification must be certified for the connection tenant'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER greythr_connection_certification_evidence
AFTER UPDATE OF capability_certification_id ON greythr_connections
FOR EACH ROW EXECUTE FUNCTION greythr_capture_certification_evidence();

CREATE TRIGGER greythr_certification_evidence_immutable
BEFORE UPDATE OR DELETE ON greythr_certification_evidence
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

CREATE TRIGGER greythr_certification_upgrade_audit_immutable
BEFORE UPDATE OR DELETE ON greythr_certification_upgrade_audit
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

CREATE TABLE greythr_reconciliation_transitions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reconciliation_id UUID NOT NULL
        REFERENCES greythr_reconciliation_items(id),
    connection_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    transition_sequence INTEGER NOT NULL CHECK (transition_sequence > 0),
    from_status VARCHAR(24),
    to_status VARCHAR(24) NOT NULL
        CHECK (to_status IN ('PENDING', 'USE_GREYTHR', 'KEEP_INTERNAL')),
    decision_reason TEXT,
    decided_at TIMESTAMPTZ,
    decided_by_subject VARCHAR(255),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_greythr_reconciliation_transition
        UNIQUE (reconciliation_id, transition_sequence),
    CONSTRAINT fk_greythr_reconciliation_transition_tenant
        FOREIGN KEY (connection_id, organization_id)
        REFERENCES greythr_connections(id, organization_id),
    CONSTRAINT ck_greythr_reconciliation_transition_state
        CHECK (
            (to_status = 'PENDING'
                AND decision_reason IS NULL
                AND decided_at IS NULL
                AND decided_by_subject IS NULL)
            OR
            (to_status IN ('USE_GREYTHR', 'KEEP_INTERNAL')
                AND decision_reason IS NOT NULL
                AND btrim(decision_reason) <> ''
                AND decided_at IS NOT NULL
                AND decided_by_subject IS NOT NULL
                AND btrim(decided_by_subject) <> '')
        )
);

INSERT INTO greythr_reconciliation_transitions(
    reconciliation_id, connection_id, organization_id, transition_sequence,
    from_status, to_status, decision_reason, decided_at, decided_by_subject,
    recorded_at
)
SELECT id, connection_id, organization_id, 1, NULL, status, decision_reason,
       decided_at, decided_by_subject, COALESCE(decided_at, CURRENT_TIMESTAMP)
FROM greythr_reconciliation_items;

CREATE FUNCTION greythr_capture_reconciliation_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
DECLARE
    next_sequence INTEGER;
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.status IS NOT DISTINCT FROM OLD.status THEN
        RETURN NEW;
    END IF;
    SELECT COALESCE(max(transition_sequence), 0) + 1
    INTO next_sequence
    FROM public.greythr_reconciliation_transitions
    WHERE reconciliation_id = NEW.id;
    INSERT INTO public.greythr_reconciliation_transitions(
        reconciliation_id, connection_id, organization_id,
        transition_sequence, from_status, to_status, decision_reason,
        decided_at, decided_by_subject
    ) VALUES (
        NEW.id, NEW.connection_id, NEW.organization_id, next_sequence,
        CASE WHEN TG_OP = 'UPDATE' THEN OLD.status ELSE NULL END,
        NEW.status, NEW.decision_reason, NEW.decided_at, NEW.decided_by_subject
    );
    RETURN NEW;
END;
$$;

CREATE TRIGGER greythr_reconciliation_transition_evidence
AFTER INSERT OR UPDATE OF status ON greythr_reconciliation_items
FOR EACH ROW EXECUTE FUNCTION greythr_capture_reconciliation_transition();

CREATE TRIGGER greythr_reconciliation_transitions_immutable
BEFORE UPDATE OR DELETE ON greythr_reconciliation_transitions
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

CREATE TRIGGER greythr_cutovers_immutable
BEFORE UPDATE OR DELETE ON greythr_cutovers
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

CREATE TABLE greythr_employee_mappings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    connection_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    provider_employee_id VARCHAR(128) NOT NULL,
    provider_employee_number VARCHAR(64) NOT NULL,
    employee_id UUID NOT NULL,
    mapping_version INTEGER NOT NULL CHECK (mapping_version > 0),
    provider_record_id VARCHAR(255) NOT NULL,
    source_fact_id UUID NOT NULL,
    source_sync_run_id UUID NOT NULL,
    payload_hash VARCHAR(64) NOT NULL CHECK (length(payload_hash) = 64),
    source_updated_at TIMESTAMPTZ NOT NULL,
    supersedes_mapping_id UUID,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_greythr_employee_mapping_version
        UNIQUE (connection_id, provider_employee_id, mapping_version),
    CONSTRAINT uq_greythr_employee_mapping_source_fact UNIQUE (source_fact_id),
    CONSTRAINT uq_greythr_employee_mapping_chain
        UNIQUE (id, connection_id, organization_id, provider_employee_id),
    CONSTRAINT fk_greythr_employee_mapping_connection
        FOREIGN KEY (connection_id, organization_id)
        REFERENCES greythr_connections(id, organization_id),
    CONSTRAINT fk_greythr_employee_mapping_employee
        FOREIGN KEY (employee_id, organization_id)
        REFERENCES employees(id, organization_id),
    CONSTRAINT fk_greythr_employee_mapping_fact
        FOREIGN KEY (source_fact_id, connection_id, organization_id)
        REFERENCES greythr_imported_facts(id, connection_id, organization_id),
    CONSTRAINT fk_greythr_employee_mapping_run
        FOREIGN KEY (source_sync_run_id, connection_id, organization_id)
        REFERENCES greythr_sync_runs(id, connection_id, organization_id),
    CONSTRAINT ck_greythr_employee_mapping_ids_nonblank
        CHECK (
            btrim(provider_employee_id) <> ''
            AND btrim(provider_employee_number) <> ''
            AND btrim(provider_record_id) <> ''
        )
);

WITH mapping_history AS (
    SELECT fact.*,
           row_number() OVER (
               PARTITION BY connection_id, provider_employee_id
               ORDER BY source_updated_at, recorded_at, id
           ) AS mapping_version,
           lag(id) OVER (
               PARTITION BY connection_id, provider_employee_id
               ORDER BY source_updated_at, recorded_at, id
           ) AS prior_mapping_source_fact
    FROM greythr_imported_facts fact
    WHERE fact.fact_kind = 'EMPLOYEE'
),
inserted AS (
    INSERT INTO greythr_employee_mappings(
        connection_id, organization_id, provider_employee_id,
        provider_employee_number, employee_id, mapping_version,
        provider_record_id, source_fact_id,
        source_sync_run_id, payload_hash, source_updated_at
    )
    SELECT connection_id, organization_id, provider_employee_id,
           payload ->> 'employeeNumber', employee_id, mapping_version,
           provider_record_id, id, sync_run_id, payload_hash, source_updated_at
    FROM mapping_history
    ORDER BY connection_id, provider_employee_id, mapping_version
    RETURNING id, source_fact_id
)
UPDATE greythr_employee_mappings mapping
SET supersedes_mapping_id = prior.id
FROM mapping_history history
JOIN inserted prior
  ON prior.source_fact_id = history.prior_mapping_source_fact
WHERE mapping.source_fact_id = history.id
  AND history.prior_mapping_source_fact IS NOT NULL;

ALTER TABLE greythr_employee_mappings
    ADD CONSTRAINT fk_greythr_employee_mapping_supersedes
        FOREIGN KEY (
            supersedes_mapping_id, connection_id, organization_id,
            provider_employee_id
        )
        REFERENCES greythr_employee_mappings(
            id, connection_id, organization_id, provider_employee_id
        ),
    ADD CONSTRAINT ck_greythr_employee_mapping_not_self
        CHECK (supersedes_mapping_id IS NULL OR supersedes_mapping_id <> id);

CREATE FUNCTION greythr_link_corrected_fact()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
DECLARE
    prior_source_updated_at TIMESTAMPTZ;
    prior_id UUID;
BEGIN
    IF NEW.supersedes_id IS NULL THEN
        SELECT prior.id, prior.source_updated_at
        INTO prior_id, prior_source_updated_at
        FROM public.greythr_imported_facts prior
        WHERE prior.connection_id = NEW.connection_id
          AND prior.organization_id = NEW.organization_id
          AND prior.fact_kind = NEW.fact_kind
          AND prior.provider_record_id = NEW.provider_record_id
          AND prior.payload_hash <> NEW.payload_hash
        ORDER BY prior.source_updated_at DESC, prior.recorded_at DESC, prior.id DESC
        LIMIT 1;
        NEW.supersedes_id := prior_id;
    ELSE
        SELECT prior.source_updated_at
        INTO prior_source_updated_at
        FROM public.greythr_imported_facts prior
        WHERE prior.id = NEW.supersedes_id;
    END IF;
    IF prior_source_updated_at IS NOT NULL
       AND NEW.source_updated_at < prior_source_updated_at THEN
        RAISE EXCEPTION
            'corrected greytHR facts cannot move provider time backwards'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER greythr_imported_fact_supersession
BEFORE INSERT ON greythr_imported_facts
FOR EACH ROW EXECUTE FUNCTION greythr_link_corrected_fact();

CREATE FUNCTION greythr_capture_employee_mapping()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
DECLARE
    prior_mapping public.greythr_employee_mappings%ROWTYPE;
BEGIN
    IF NEW.fact_kind <> 'EMPLOYEE' THEN
        RETURN NEW;
    END IF;
    PERFORM pg_advisory_xact_lock(
        hashtextextended(NEW.connection_id::text || ':' ||
                         NEW.provider_employee_id, 0)
    );
    SELECT *
    INTO prior_mapping
    FROM public.greythr_employee_mappings
    WHERE connection_id = NEW.connection_id
      AND provider_employee_id = NEW.provider_employee_id
    ORDER BY mapping_version DESC
    LIMIT 1;
    INSERT INTO public.greythr_employee_mappings(
        connection_id, organization_id, provider_employee_id,
        provider_employee_number, employee_id, mapping_version,
        provider_record_id, source_fact_id,
        source_sync_run_id, payload_hash, source_updated_at,
        supersedes_mapping_id
    ) VALUES (
        NEW.connection_id, NEW.organization_id, NEW.provider_employee_id,
        NEW.payload ->> 'employeeNumber', NEW.employee_id,
        COALESCE(prior_mapping.mapping_version, 0) + 1,
        NEW.provider_record_id, NEW.id, NEW.sync_run_id,
        NEW.payload_hash, NEW.source_updated_at, prior_mapping.id
    );
    RETURN NEW;
END;
$$;

CREATE TRIGGER greythr_employee_mapping_lineage
AFTER INSERT ON greythr_imported_facts
FOR EACH ROW EXECUTE FUNCTION greythr_capture_employee_mapping();

CREATE TRIGGER greythr_employee_mappings_immutable
BEFORE UPDATE OR DELETE ON greythr_employee_mappings
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

CREATE TABLE greythr_fact_applications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    connection_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    provider_fact_id UUID NOT NULL,
    action VARCHAR(24) NOT NULL
        CHECK (action IN ('APPLY', 'SUPERSEDE', 'COMPENSATE')),
    target_kind VARCHAR(32) NOT NULL
        CHECK (target_kind IN (
            'ATTENDANCE_EVENT', 'ATTENDANCE_SESSION',
            'LEAVE_BALANCE_LEDGER', 'RECONCILIATION'
        )),
    target_record_id UUID,
    supersedes_application_id UUID,
    correlation_id UUID NOT NULL,
    reason TEXT NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    applied_by_subject VARCHAR(255) NOT NULL,
    CONSTRAINT uq_greythr_fact_application_id_tenant
        UNIQUE (id, connection_id, organization_id),
    CONSTRAINT uq_greythr_fact_application_effect
        UNIQUE (provider_fact_id, action, target_kind, target_record_id),
    CONSTRAINT fk_greythr_fact_application_connection
        FOREIGN KEY (connection_id, organization_id)
        REFERENCES greythr_connections(id, organization_id),
    CONSTRAINT fk_greythr_fact_application_fact
        FOREIGN KEY (provider_fact_id, connection_id, organization_id)
        REFERENCES greythr_imported_facts(id, connection_id, organization_id),
    CONSTRAINT ck_greythr_fact_application_reason
        CHECK (btrim(reason) <> '' AND btrim(applied_by_subject) <> ''),
    CONSTRAINT ck_greythr_fact_application_reason_limit
        CHECK (length(reason) <= 4000),
    CONSTRAINT ck_greythr_fact_application_metadata_no_commercial_data
        CHECK (f07_assert_no_commercial_fields(metadata)),
    CONSTRAINT ck_greythr_fact_application_target
        CHECK (action = 'COMPENSATE' OR target_record_id IS NOT NULL),
    CONSTRAINT ck_greythr_fact_application_not_self
        CHECK (
            supersedes_application_id IS NULL
            OR supersedes_application_id <> id
        )
);

ALTER TABLE greythr_fact_applications
    ADD CONSTRAINT fk_greythr_fact_application_supersedes
        FOREIGN KEY (
            supersedes_application_id, connection_id, organization_id
        )
        REFERENCES greythr_fact_applications(
            id, connection_id, organization_id
        );

CREATE TRIGGER greythr_fact_applications_immutable
BEFORE UPDATE OR DELETE ON greythr_fact_applications
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

CREATE INDEX idx_greythr_mapping_current
    ON greythr_employee_mappings(
        connection_id, provider_employee_id, mapping_version DESC
    );
CREATE INDEX idx_greythr_fact_supersession
    ON greythr_imported_facts(
        connection_id, fact_kind, provider_record_id, source_updated_at DESC
    );
CREATE INDEX idx_greythr_fact_application_lineage
    ON greythr_fact_applications(provider_fact_id, applied_at, id);

-- V25 was created after the least-privilege baseline, so explicitly repair its
-- ownership/grants together with every V26 object. Evidence/lineage is
-- append-only to runtime; reporting and worker principals receive no access.
DO $$
DECLARE
    table_name TEXT;
    function_name TEXT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'greythr_connections',
        'greythr_recorded_pages',
        'greythr_sync_runs',
        'greythr_imported_facts',
        'greythr_reconciliation_items',
        'greythr_cutovers',
        'greythr_certification_evidence',
        'greythr_certification_upgrade_audit',
        'greythr_reconciliation_transitions',
        'greythr_employee_mappings',
        'greythr_fact_applications'
    ]
    LOOP
        EXECUTE format(
            'ALTER TABLE public.%I OWNER TO vms_migration_owner',
            table_name);
        EXECUTE format(
            'REVOKE ALL ON TABLE public.%I FROM PUBLIC, vms_reporting, '
            'vms_job_worker, vms_migration_processor',
            table_name);
        EXECUTE format(
            'GRANT SELECT ON TABLE public.%I TO vms_backup',
            table_name);
    END LOOP;

    FOREACH function_name IN ARRAY ARRAY[
        'greythr_capture_certification_evidence',
        'greythr_capture_reconciliation_transition',
        'greythr_link_corrected_fact',
        'greythr_capture_employee_mapping'
    ]
    LOOP
        EXECUTE format(
            'ALTER FUNCTION public.%I() OWNER TO vms_migration_owner',
            function_name);
        EXECUTE format(
            'ALTER FUNCTION public.%I() SET search_path = pg_catalog, public',
            function_name);
        EXECUTE format(
            'REVOKE ALL ON FUNCTION public.%I() FROM PUBLIC',
            function_name);
        EXECUTE format(
            'GRANT EXECUTE ON FUNCTION public.%I() TO vms_app_runtime',
            function_name);
    END LOOP;
END;
$$;

GRANT SELECT, UPDATE ON
    greythr_connections
TO vms_app_runtime;

GRANT SELECT ON
    greythr_recorded_pages
TO vms_app_runtime;

GRANT SELECT, INSERT, UPDATE ON
    greythr_sync_runs,
    greythr_reconciliation_items
TO vms_app_runtime;

GRANT SELECT, INSERT ON
    greythr_imported_facts,
    greythr_cutovers,
    greythr_certification_evidence,
    greythr_reconciliation_transitions,
    greythr_employee_mappings,
    greythr_fact_applications
TO vms_app_runtime;

REVOKE UPDATE, DELETE, TRUNCATE ON
    greythr_imported_facts,
    greythr_cutovers,
    greythr_certification_evidence,
    greythr_certification_upgrade_audit,
    greythr_reconciliation_transitions,
    greythr_employee_mappings,
    greythr_fact_applications
FROM vms_app_runtime;
