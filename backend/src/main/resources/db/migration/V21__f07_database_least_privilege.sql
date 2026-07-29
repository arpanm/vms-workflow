-- F07 database boundary. These are NOLOGIN capability roles: production login
-- roles are provisioned externally, receive only the applicable capability
-- role, and use separate credentials for Flyway and runtime connections.

-- Ownership transfer requires ACCESS EXCLUSIVE locks. A controlled deployment
-- must quiesce writers before V21; fail quickly instead of waiting indefinitely
-- if an unexpected live transaction still holds a relation lock.
SET LOCAL lock_timeout = '5s';

DO $$
DECLARE
    role_name TEXT;
    role_attributes RECORD;
BEGIN
    FOREACH role_name IN ARRAY ARRAY[
        'vms_migration_owner',
        'vms_app_runtime',
        'vms_reporting',
        'vms_job_worker',
        'vms_migration_processor',
        'vms_backup'
    ]
    LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = role_name) THEN
            EXECUTE format(
                'CREATE ROLE %I NOLOGIN NOSUPERUSER NOCREATEDB '
                'NOCREATEROLE INHERIT NOREPLICATION NOBYPASSRLS',
                role_name);
        ELSE
            SELECT rolcanlogin, rolsuper, rolcreatedb, rolcreaterole,
                   rolinherit, rolreplication, rolbypassrls
            INTO role_attributes
            FROM pg_roles
            WHERE rolname = role_name;
            IF role_attributes.rolcanlogin
               OR role_attributes.rolsuper
               OR role_attributes.rolcreatedb
               OR role_attributes.rolcreaterole
               OR NOT role_attributes.rolinherit
               OR role_attributes.rolreplication
               OR role_attributes.rolbypassrls THEN
                RAISE EXCEPTION
                    'F07 capability role % has incompatible attributes',
                    role_name;
            END IF;
        END IF;
    END LOOP;
END;
$$;

CREATE TABLE f07_database_role_policy (
    role_name TEXT PRIMARY KEY,
    access_class TEXT NOT NULL CHECK (access_class IN (
        'MIGRATION', 'RUNTIME', 'REPORTING', 'WORKER', 'BACKUP'
    )),
    purpose TEXT NOT NULL,
    login_permitted BOOLEAN NOT NULL DEFAULT FALSE,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO f07_database_role_policy(role_name, access_class, purpose)
VALUES
    ('vms_migration_owner', 'MIGRATION',
     'Flyway DDL and controlled grants; never used by the application pool'),
    ('vms_app_runtime', 'RUNTIME',
     'Interactive API reads and business writes without DDL or Flyway access'),
    ('vms_reporting', 'REPORTING',
     'Read-only business reporting without secrets, tokens, blobs or security events'),
    ('vms_job_worker', 'WORKER',
     'Bounded certification and finance queue work without DDL or Flyway access'),
    ('vms_migration_processor', 'WORKER',
     'Lease-bound migration scan and validation recovery without identity, RBAC or direct source-blob access'),
    ('vms_backup', 'BACKUP',
     'Read-only backup coverage including restricted persisted records');

CREATE TRIGGER f07_database_role_policy_immutable
BEFORE UPDATE OR DELETE ON f07_database_role_policy
FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

CREATE TABLE f07_rate_limit_buckets (
    principal_hash VARCHAR(64) NOT NULL,
    client_address_hash VARCHAR(64) NOT NULL,
    operation VARCHAR(64) NOT NULL,
    bucket_start TIMESTAMPTZ NOT NULL,
    request_count INTEGER NOT NULL CHECK (request_count > 0),
    PRIMARY KEY (
        principal_hash, client_address_hash, operation, bucket_start
    )
);

REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO vms_migration_owner;
GRANT USAGE ON SCHEMA public TO
    vms_app_runtime, vms_reporting, vms_job_worker,
    vms_migration_processor, vms_backup;

REVOKE ALL ON ALL TABLES IN SCHEMA public FROM PUBLIC;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC;

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public
    TO vms_app_runtime;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public
    TO vms_app_runtime;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public
    TO vms_app_runtime;

-- The finance/certification worker is an explicit queue/attempt principal, not
-- a second application role. It cannot enumerate identity/RBAC, provider
-- credentials, migration sources or private artifact bytes.
GRANT SELECT ON
    confirmation_request_schedules,
    business_confirmation_requests,
    business_confirmation_actions,
    notification_outbox,
    certification_readiness_runs,
    f05_outbox,
    f05_handoff_publish_jobs,
    f05_handoff_publish_attempts,
    f05_certification_handoffs,
    f05_handoff_invalidations,
    f05_domain_events,
    f05_audit_events,
    f05_report_exports,
    engagements,
    engagement_months,
    attendance_snapshot_versions,
    attendance_snapshot_days,
    delivery_plans,
    delivery_plan_versions,
    delivery_plan_baselines,
    delivery_submissions,
    deliverable_delivery_outcomes,
    deliverable_certifications,
    evidence_package_versions,
    invoices,
    invoice_readiness_runs,
    invoice_readiness_results,
    procurement_queries,
    procurement_query_responses,
    procurement_reviews,
    procurement_exceptions,
    payment_status_history,
    confirmation_eligibility_snapshots,
    confirmation_request_eligibility,
    delivery_recipient_snapshots
TO vms_job_worker;

GRANT INSERT, UPDATE ON
    confirmation_request_schedules,
    notification_outbox,
    notification_delivery_attempts,
    f05_outbox,
    f05_handoff_publish_jobs,
    f05_report_exports
TO vms_job_worker;

GRANT UPDATE (status, optimistic_version)
    ON business_confirmation_requests TO vms_job_worker;
GRANT UPDATE (certification_version, state, updated_at)
    ON engagement_months TO vms_job_worker;

GRANT INSERT ON
    certification_audit_events,
    certification_security_events,
    certification_domain_events,
    delivery_audit_events,
    f05_audit_events,
    f05_security_events,
    migration_audit_events,
    migration_security_events
TO vms_job_worker;

GRANT INSERT ON
    f05_private_artifact_blobs,
    f05_private_artifacts,
    f05_handoff_publish_attempts,
    f05_domain_events
TO vms_job_worker;
GRANT SELECT (
    id, request_id, expires_at, consumed_at
) ON confirmation_secure_tokens TO vms_job_worker;
GRANT SELECT (
    token_id, request_id, outbox_id, encrypted_token, nonce, status
) ON confirmation_token_handoffs TO vms_job_worker;
GRANT UPDATE (
    status, delivered_at, revoked_at, failure_code
) ON confirmation_token_handoffs TO vms_job_worker;
GRANT SELECT (token_id) ON confirmation_token_revocations TO vms_job_worker;
GRANT INSERT ON confirmation_token_revocations TO vms_job_worker;

-- Migration recovery is deliberately isolated from finance/certification.
-- It can validate staged content but cannot execute commit/rollback paths,
-- enumerate application identities/RBAC, or read source bytes directly.
GRANT SELECT ON
    organizations,
    engagements,
    engagement_months,
    projects,
    employees,
    attendance_snapshot_versions,
    evidence_package_versions,
    invoices,
    migration_jobs,
    migration_source_files,
    migration_scan_verdicts,
    migration_validation_attempts,
    migration_decisions,
    migration_checkpoints,
    migration_dependencies,
    migration_rows,
    migration_row_findings,
    migration_reconciliation_reports,
    migration_canonical_facts,
    migration_domain_provenance
TO vms_migration_processor;
GRANT UPDATE (
    state, version, lease_owner, lease_until, retry_count, dead_lettered_at,
    row_count, valid_count, warning_count, invalid_count
) ON migration_jobs TO vms_migration_processor;
GRANT UPDATE (scan_status, scan_reason_code)
    ON migration_source_files TO vms_migration_processor;
GRANT INSERT ON
    migration_scan_verdicts,
    migration_validation_attempts,
    migration_decisions,
    migration_checkpoints,
    migration_rows,
    migration_row_findings,
    migration_reconciliation_reports,
    migration_audit_events
TO vms_migration_processor;
GRANT UPDATE ON
    migration_validation_attempts,
    migration_checkpoints
TO vms_migration_processor;

CREATE FUNCTION f07_migration_leased_source(
    requested_job_id UUID,
    requested_lease_owner TEXT
) RETURNS BYTEA
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT blob.content
    FROM public.migration_jobs job
    JOIN public.migration_source_blobs blob
      ON blob.source_file_id = job.source_file_id
    WHERE job.id = requested_job_id
      AND job.lease_owner = requested_lease_owner
      AND job.lease_until > CURRENT_TIMESTAMP
$$;
REVOKE ALL ON FUNCTION
    f07_migration_leased_source(UUID, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION
    f07_migration_leased_source(UUID, TEXT) TO vms_migration_processor;

GRANT SELECT ON ALL TABLES IN SCHEMA public TO vms_backup;
GRANT SELECT ON ALL SEQUENCES IN SCHEMA public TO vms_backup;

-- The reporting principal never receives blanket table access. This view is
-- an explicit non-personal, non-secret allowlist surface.
CREATE VIEW f07_reporting_month_status
WITH (security_barrier = true)
AS
SELECT month.id AS month_id,
       month.month_start_date,
       month.state,
       month.risk_status,
       engagement.id AS engagement_id,
       engagement.engagement_code,
       engagement.status AS engagement_status
FROM engagement_months month
JOIN engagements engagement ON engagement.id = month.engagement_id;

GRANT SELECT ON f07_reporting_month_status TO vms_reporting;

-- Schema history and role policy are migration-owned configuration. Runtime
-- and worker roles do not even receive SELECT, preventing enumeration as well
-- as mutation.
REVOKE ALL ON TABLE flyway_schema_history, f07_database_role_policy
    FROM vms_app_runtime, vms_job_worker, vms_migration_processor;
REVOKE ALL ON TABLE flyway_schema_history
    FROM vms_reporting;

-- Append-only audit/security streams permit application append and restricted
-- reads, but never update/delete/truncate. Reporting has no implicit security
-- event access.
DO $$
DECLARE
    item RECORD;
BEGIN
    FOR item IN
        SELECT schemaname, tablename
        FROM pg_tables
        WHERE schemaname = 'public'
          AND tablename <> 'flyway_schema_history'
          AND (
              tablename LIKE '%audit%'
              OR tablename LIKE '%security_event%'
          )
    LOOP
        EXECUTE format(
            'REVOKE UPDATE, DELETE, TRUNCATE ON TABLE %I.%I '
            'FROM vms_app_runtime, vms_job_worker, vms_migration_processor',
            item.schemaname, item.tablename);
        EXECUTE format(
            'REVOKE ALL ON TABLE %I.%I FROM vms_reporting',
            item.schemaname, item.tablename);
    END LOOP;
END;
$$;

-- Flyway holds a separate lock on its history relation while a migration is
-- running, so ownership cannot be transferred safely from inside V21. The
-- capability role receives the exact history privileges it needs instead.
GRANT SELECT, INSERT, UPDATE, DELETE
    ON TABLE flyway_schema_history TO vms_migration_owner;

-- Binary payloads and bearer/confirmation-token material are outside the
-- general reporting role. Backup remains read-only and separately controlled.
REVOKE ALL ON TABLE
    linear_connections,
    migration_source_blobs,
    confirmation_secure_tokens,
    confirmation_token_handoffs,
    confirmation_token_revocations,
    f05_private_artifact_blobs,
    migration_source_blobs
FROM vms_reporting;

-- The controlled migration login must own the pre-V21 schema and be a member
-- of vms_migration_owner. V21 runs as that login, transfers ownership once,
-- and future rotated migration logins receive membership in the NOLOGIN
-- capability role. Do not SET ROLE before V21: the capability role does not
-- yet own the legacy schema.
DO $$
DECLARE
    item RECORD;
BEGIN
    FOR item IN
        SELECT schemaname, tablename
        FROM pg_tables
        WHERE schemaname = 'public'
          AND tablename <> 'flyway_schema_history'
    LOOP
        EXECUTE format(
            'ALTER TABLE %I.%I OWNER TO vms_migration_owner',
            item.schemaname, item.tablename);
    END LOOP;
    FOR item IN
        SELECT sequence_schema, sequence_name
        FROM information_schema.sequences
        WHERE sequence_schema = 'public'
    LOOP
        EXECUTE format(
            'ALTER SEQUENCE %I.%I OWNER TO vms_migration_owner',
            item.sequence_schema, item.sequence_name);
    END LOOP;
    FOR item IN
        SELECT schemaname, viewname
        FROM pg_views
        WHERE schemaname = 'public'
    LOOP
        EXECUTE format(
            'ALTER VIEW %I.%I OWNER TO vms_migration_owner',
            item.schemaname, item.viewname);
    END LOOP;
    FOR item IN
        SELECT namespace.nspname,
               procedure.proname,
               pg_get_function_identity_arguments(procedure.oid) AS arguments
        FROM pg_proc procedure
        JOIN pg_namespace namespace ON namespace.oid = procedure.pronamespace
        WHERE namespace.nspname = 'public'
          AND procedure.prokind = 'f'
          AND NOT EXISTS (
              SELECT 1
              FROM pg_depend dependency
              WHERE dependency.classid = 'pg_proc'::regclass
                AND dependency.objid = procedure.oid
                AND dependency.deptype = 'e'
          )
    LOOP
        EXECUTE format(
            'ALTER FUNCTION %I.%I(%s) OWNER TO vms_migration_owner',
            item.nspname, item.proname, item.arguments);
    END LOOP;
END;
$$;

-- Every application function except the explicitly allowlisted leased-source
-- function is invoker-rights. Pin every function's resolution path so a
-- hostile session schema cannot shadow a referenced relation or helper.
DO $$
DECLARE
    item RECORD;
BEGIN
    FOR item IN
        SELECT namespace.nspname,
               procedure.proname,
               pg_get_function_identity_arguments(procedure.oid) AS arguments
        FROM pg_proc procedure
        JOIN pg_namespace namespace ON namespace.oid = procedure.pronamespace
        WHERE namespace.nspname = 'public'
          AND procedure.prokind = 'f'
          AND NOT EXISTS (
              SELECT 1
              FROM pg_depend dependency
              WHERE dependency.classid = 'pg_proc'::regclass
                AND dependency.objid = procedure.oid
                AND dependency.deptype = 'e'
          )
    LOOP
        EXECUTE format(
            'ALTER FUNCTION %I.%I(%s) SET search_path = pg_catalog, public',
            item.nspname, item.proname, item.arguments);
    END LOOP;
END;
$$;

-- Secure defaults force every future forward migration to grant newly-created
-- objects intentionally. They must not inherit PostgreSQL's PUBLIC function
-- execution or schema access defaults.
ALTER DEFAULT PRIVILEGES FOR ROLE vms_migration_owner IN SCHEMA public
    REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE vms_migration_owner IN SCHEMA public
    REVOKE ALL ON SEQUENCES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE vms_migration_owner IN SCHEMA public
    REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;

-- V21 and later migrations may create objects as the dedicated LOGIN before
-- explicitly transferring ownership. Secure that login's creation defaults
-- as well; capability-role defaults alone do not apply to session_user.
DO $$
BEGIN
    EXECUTE format(
        'ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public '
        'REVOKE ALL ON TABLES FROM PUBLIC',
        session_user);
    EXECUTE format(
        'ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public '
        'REVOKE ALL ON SEQUENCES FROM PUBLIC',
        session_user);
    EXECUTE format(
        'ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public '
        'REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC',
        session_user);
END;
$$;
