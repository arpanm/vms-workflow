-- A caller declaration is not provider evidence. Capability certification is
-- now bound to one immutable, bounded adapter probe before sync or cutover can
-- use it. Recorded fixtures are explicitly simulated non-production evidence.
CREATE TABLE greythr_capability_probe_evidence (
    id UUID PRIMARY KEY,
    connection_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    adapter_mode VARCHAR(24) NOT NULL
        CHECK (adapter_mode IN ('RECORDED_FIXTURE', 'PROVIDER_NEUTRAL')),
    authority_classification VARCHAR(40) NOT NULL
        DEFAULT 'SIMULATED_NON_PRODUCTION'
        CHECK (authority_classification = 'SIMULATED_NON_PRODUCTION'),
    status VARCHAR(24) NOT NULL CHECK (status = 'PASSED'),
    capabilities JSONB NOT NULL,
    evidence_manifest JSONB NOT NULL,
    evidence_hash VARCHAR(64) NOT NULL
        CHECK (evidence_hash ~ '^[0-9a-f]{64}$'),
    probed_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_greythr_probe_connection_tenant
        UNIQUE (id, connection_id, organization_id),
    CONSTRAINT fk_greythr_probe_connection_tenant
        FOREIGN KEY (connection_id, organization_id)
        REFERENCES greythr_connections(id, organization_id),
    CONSTRAINT ck_greythr_probe_capabilities_exact
        CHECK (
            jsonb_typeof(capabilities) = 'array'
            AND jsonb_array_length(capabilities) = 3
            AND capabilities @> '[
                "EMPLOYEES", "ATTENDANCE", "LEAVE"
            ]'::jsonb
        ),
    CONSTRAINT ck_greythr_probe_manifest_bounded
        CHECK (pg_column_size(evidence_manifest) <= 1048576),
    CONSTRAINT ck_greythr_probe_no_commercial_data
        CHECK (f07_assert_no_commercial_fields(evidence_manifest))
);

ALTER TABLE greythr_certification_evidence
    ADD COLUMN provider_probe_evidence_id UUID,
    ADD CONSTRAINT fk_greythr_certification_probe_tenant
        FOREIGN KEY (
            provider_probe_evidence_id, connection_id, organization_id
        )
        REFERENCES greythr_capability_probe_evidence(
            id, connection_id, organization_id
        );

-- Certified V25/V26 declarations have no provider attestation. Preserve their
-- history but fail active connections closed until a fresh v2 probe certifies
-- them. This avoids silently treating historical declarations as live proof.
INSERT INTO greythr_certification_upgrade_audit(
    connection_id, organization_id, certification_id,
    certification_status, prior_connection_status,
    resulting_connection_status, reason_code
)
SELECT connection.id, connection.organization_id, certification.id,
       certification.status, connection.status, 'DISCOVERED',
       'PROVIDER_ATTESTATION_REQUIRED'
FROM greythr_connections connection
JOIN integration_capability_certifications certification
  ON certification.id = connection.capability_certification_id
WHERE NOT EXISTS (
    SELECT 1
    FROM greythr_certification_evidence evidence
    WHERE evidence.connection_id = connection.id
      AND evidence.certification_id = certification.id
      AND evidence.provider_probe_evidence_id IS NOT NULL
);

UPDATE greythr_connections connection
SET status = 'DISCOVERED',
    capability_certification_id = NULL,
    last_error_code = 'PROVIDER_ATTESTATION_REQUIRED'
WHERE connection.capability_certification_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM greythr_certification_evidence evidence
      WHERE evidence.connection_id = connection.id
        AND evidence.certification_id =
            connection.capability_certification_id
        AND evidence.provider_probe_evidence_id IS NOT NULL
  );

CREATE OR REPLACE FUNCTION greythr_capture_certification_evidence()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
DECLARE
    certification RECORD;
    probe_id UUID;
    probe RECORD;
BEGIN
    IF NEW.capability_certification_id IS NULL
       OR NEW.capability_certification_id
           IS NOT DISTINCT FROM OLD.capability_certification_id THEN
        RETURN NEW;
    END IF;

    SELECT item.id, item.status, item.certified_at,
           item.capability_manifest,
           item.capability_manifest #>>
               '{providerAttestation,probeEvidenceId}' AS probe_id_text,
           item.capability_manifest #>>
               '{providerAttestation,evidenceHash}' AS evidence_hash,
           item.capability_manifest #>>
               '{providerAttestation,adapterMode}' AS adapter_mode
    INTO certification
    FROM public.integration_capability_certifications item
    WHERE item.id = NEW.capability_certification_id
      AND item.organization_id = NEW.organization_id;

    IF certification.id IS NULL OR certification.status <> 'CERTIFIED'
       OR certification.probe_id_text IS NULL THEN
        RAISE EXCEPTION
            'greytHR certification requires certified provider probe evidence'
            USING ERRCODE = '23514';
    END IF;
    BEGIN
        probe_id := certification.probe_id_text::UUID;
    EXCEPTION WHEN invalid_text_representation THEN
        RAISE EXCEPTION
            'greytHR provider probe evidence identity is invalid'
            USING ERRCODE = '23514';
    END;

    SELECT evidence.id, evidence.evidence_hash, evidence.adapter_mode,
           evidence.status
    INTO probe
    FROM public.greythr_capability_probe_evidence evidence
    WHERE evidence.id = probe_id
      AND evidence.connection_id = NEW.id
      AND evidence.organization_id = NEW.organization_id;

    IF probe.id IS NULL OR probe.status <> 'PASSED'
       OR probe.evidence_hash IS DISTINCT FROM certification.evidence_hash
       OR probe.adapter_mode IS DISTINCT FROM certification.adapter_mode
       OR probe.adapter_mode IS DISTINCT FROM NEW.adapter_mode THEN
        RAISE EXCEPTION
            'greytHR certification attestation does not match the connection'
            USING ERRCODE = '23514';
    END IF;

    INSERT INTO public.greythr_certification_evidence(
        connection_id, organization_id, certification_id,
        certification_status, capability_manifest, certified_at,
        provider_probe_evidence_id
    ) VALUES (
        NEW.id, NEW.organization_id, certification.id,
        certification.status, certification.capability_manifest,
        certification.certified_at, probe.id
    );
    RETURN NEW;
END;
$$;

CREATE FUNCTION greythr_assert_authoritative_fact_application()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
DECLARE
    provider_fact RECORD;
BEGIN
    IF NEW.action = 'APPLY' AND EXISTS (
        SELECT 1
        FROM public.greythr_fact_applications prior
        WHERE prior.provider_fact_id = NEW.provider_fact_id
          AND prior.target_kind = NEW.target_kind
          AND prior.action = 'APPLY'
    ) THEN
        RAISE EXCEPTION
            'greytHR provider fact business effect is already applied'
            USING ERRCODE = '23505';
    END IF;

    SELECT fact.employee_id, fact.work_date,
           connection.capability_certification_id
    INTO provider_fact
    FROM public.greythr_imported_facts fact
    JOIN public.greythr_connections connection
      ON connection.id = fact.connection_id
     AND connection.organization_id = fact.organization_id
    WHERE fact.id = NEW.provider_fact_id
      AND fact.connection_id = NEW.connection_id
      AND fact.organization_id = NEW.organization_id;

    IF provider_fact.work_date IS NULL
       OR NOT EXISTS (
           SELECT 1
           FROM public.attendance_source_mode_assignments assignment
           JOIN public.greythr_certification_evidence certification
             ON certification.certification_id =
                    assignment.capability_certification_id
            AND certification.connection_id = NEW.connection_id
            AND certification.organization_id = NEW.organization_id
            AND certification.provider_probe_evidence_id IS NOT NULL
           WHERE assignment.employee_id = provider_fact.employee_id
             AND assignment.mode = 'GREYTHR_AUTHORITATIVE'
             AND assignment.authoritative_source = 'GREYTHR'
             AND assignment.capability_certification_id =
                    provider_fact.capability_certification_id
             AND assignment.valid_from <= provider_fact.work_date
             AND (
                 assignment.valid_to IS NULL
                 OR assignment.valid_to >= provider_fact.work_date
             )
       ) THEN
        RAISE EXCEPTION
            'greytHR fact application requires effective attested authority'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER greythr_fact_application_authority_gate
BEFORE INSERT ON greythr_fact_applications
FOR EACH ROW EXECUTE FUNCTION greythr_assert_authoritative_fact_application();

CREATE FUNCTION greythr_assert_attested_cutover()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM public.greythr_certification_evidence certification
        JOIN public.greythr_capability_probe_evidence probe
          ON probe.id = certification.provider_probe_evidence_id
         AND probe.connection_id = certification.connection_id
         AND probe.organization_id = certification.organization_id
        WHERE certification.connection_id = NEW.connection_id
          AND certification.organization_id = NEW.organization_id
          AND certification.certification_id =
              NEW.capability_certification_id
          AND probe.status = 'PASSED'
    ) THEN
        RAISE EXCEPTION
            'greytHR cutover requires attested provider capability evidence'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER greythr_cutover_attestation_gate
BEFORE INSERT ON greythr_cutovers
FOR EACH ROW EXECUTE FUNCTION greythr_assert_attested_cutover();

-- Keep PostgreSQL direct-write enforcement in exact parity with the recursive
-- Java validator, including nested payroll, compensation, bank and CTC keys.
CREATE OR REPLACE FUNCTION f07_assert_no_commercial_fields(value JSONB)
RETURNS BOOLEAN
LANGUAGE plpgsql
IMMUTABLE
SET search_path = pg_catalog, public
AS $$
DECLARE
    item RECORD;
    normalized_key TEXT;
BEGIN
    IF value IS NULL THEN
        RETURN TRUE;
    END IF;
    IF jsonb_typeof(value) = 'object' THEN
        FOR item IN
            SELECT entry.key, entry.value AS nested
            FROM jsonb_each(value) entry
        LOOP
            normalized_key := regexp_replace(
                lower(item.key), '[^a-z0-9]', '', 'g');
            IF normalized_key IN (
                'accountnumber', 'amount', 'bankaccount', 'bankrouting',
                'commercialterms', 'cost', 'ctc', 'currency', 'iban',
                'invoice', 'invoicenumber', 'margin', 'nationalid',
                'pannumber', 'payroll', 'ponumber', 'price',
                'purchaseorder', 'rate', 'rateamount', 'rateband',
                'ratecard', 'ratepercent', 'rates', 'ratevalue',
                'routingnumber', 'ssn', 'swift', 'taxid'
            )
               OR normalized_key ~
                  '(salary|markup|payroll|compensation|bankaccount|aadhaar)'
               OR normalized_key ~
                  '^(billing|cost|hourly|commercial|vendor|client|employee).*rate.*$'
               OR normalized_key ~
                  '^(base|gross|net|annual|monthly|daily|pay).*salary.*$'
            THEN
                RAISE EXCEPTION 'Prohibited commercial field'
                    USING ERRCODE = '22023';
            END IF;
            PERFORM f07_assert_no_commercial_fields(item.nested);
        END LOOP;
    ELSIF jsonb_typeof(value) = 'array' THEN
        FOR item IN
            SELECT entry.value AS nested
            FROM jsonb_array_elements(value) entry
        LOOP
            PERFORM f07_assert_no_commercial_fields(item.nested);
        END LOOP;
    END IF;
    RETURN TRUE;
END;
$$;

ALTER TABLE greythr_capability_probe_evidence
    OWNER TO vms_migration_owner;
REVOKE ALL ON TABLE greythr_capability_probe_evidence
    FROM PUBLIC, vms_reporting, vms_job_worker, vms_migration_processor;
GRANT SELECT, INSERT ON greythr_capability_probe_evidence
    TO vms_app_runtime;
GRANT SELECT ON greythr_capability_probe_evidence TO vms_backup;
REVOKE UPDATE, DELETE, TRUNCATE ON greythr_capability_probe_evidence
    FROM vms_app_runtime;

CREATE TRIGGER greythr_capability_probe_evidence_immutable
BEFORE UPDATE OR DELETE ON greythr_capability_probe_evidence
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

DO $$
DECLARE
    function_name TEXT;
BEGIN
    FOREACH function_name IN ARRAY ARRAY[
        'greythr_capture_certification_evidence',
        'greythr_assert_authoritative_fact_application',
        'greythr_assert_attested_cutover'
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
