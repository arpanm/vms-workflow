package com.vms.workflow.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:tc:vmspostgresql:18-alpine:///vms_workflow",
    "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.flyway.locations=classpath:db/migration,classpath:db/testdata",
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:9/test-jwks",
    "vms.security.issuer=https://issuer.example.test",
    "vms.security.audience=vms-api"
})
class F07DatabaseRoleIT {
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void runtimeRoleCannotCreateObjectsOrReadAndMutateFlywayHistory()
        throws Exception {
        try (Connection connection = jdbc.getDataSource().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE vms_app_runtime");
            assertThrows(
                java.sql.SQLException.class,
                () -> statement.execute("CREATE TABLE f07_forbidden(id integer)"));
            assertThrows(
                java.sql.SQLException.class,
                () -> statement.execute(
                    "UPDATE flyway_schema_history SET success = FALSE"));
        }
    }

    @Test
    void reportingIsReadOnlyAndRestrictedPayloadsAreNotGranted()
        throws Exception {
        try (Connection connection = jdbc.getDataSource().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE vms_reporting");
            try (ResultSet rows =
                     statement.executeQuery(
                         "SELECT count(*) FROM f07_reporting_month_status")) {
                assertTrue(rows.next());
            }
            assertThrows(
                java.sql.SQLException.class,
                () -> statement.execute(
                    "INSERT INTO organizations(id, code, legal_name, status) "
                    + "VALUES (gen_random_uuid(), 'DENIED', 'Denied', 'ACTIVE')"));
        }
        try (Connection connection = jdbc.getDataSource().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE vms_reporting");
            assertThrows(
                java.sql.SQLException.class,
                () -> statement.execute(
                    "SELECT count(*) FROM confirmation_secure_tokens"));
        }
        try (Connection connection = jdbc.getDataSource().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE vms_reporting");
            assertThrows(
                java.sql.SQLException.class,
                () -> statement.execute(
                    "SELECT credential_secret_ref FROM linear_connections"));
            assertThrows(
                java.sql.SQLException.class,
                () -> statement.execute("SELECT email FROM user_profiles"));
        }
    }

    @Test
    void workerIsQueueBoundAndCannotReadIdentityRbacSecretsOrBlobs()
        throws Exception {
        try (Connection connection = jdbc.getDataSource().getConnection();
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("SET ROLE vms_job_worker");
            try (ResultSet rows = statement.executeQuery(
                "SELECT count(*) FROM engagement_months")) {
                assertTrue(rows.next());
            }
            UUID eventId = UUID.randomUUID();
            UUID outboxId = UUID.randomUUID();
            UUID aggregateId = UUID.randomUUID();
            statement.execute("""
                INSERT INTO f05_domain_events(
                    id, engagement_month_id, event_type, aggregate_type,
                    aggregate_id, aggregate_version, payload, actor_subject,
                    correlation_id
                ) VALUES (
                    '%s', NULL, 'F07_WORKER_ROLE_SMOKE', 'ROLE_SMOKE',
                    '%s', 1, '{}'::jsonb, 'SYSTEM:F07_TEST', '%s')
                """.formatted(eventId, aggregateId, UUID.randomUUID()));
            statement.execute("""
                INSERT INTO f05_outbox(
                    id, event_id, status, next_attempt_at
                ) VALUES ('%s', '%s', 'PENDING', CURRENT_TIMESTAMP)
                """.formatted(outboxId, eventId));
            assertEquals(1, statement.executeUpdate("""
                UPDATE f05_outbox
                SET status = 'CLAIMED',
                    lease_owner = 'f07-role-smoke',
                    lease_expires_at = CURRENT_TIMESTAMP + INTERVAL '1 minute'
                WHERE id = '%s'
                """.formatted(outboxId)));
            connection.rollback();
        }
        try (Connection connection = jdbc.getDataSource().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE vms_job_worker");
            try (ResultSet rows =
                     statement.executeQuery("SELECT count(*) FROM f05_outbox")) {
                assertTrue(rows.next());
            }
            for (String forbidden : new String[] {
                "SELECT email FROM user_profiles",
                "SELECT count(*) FROM memberships",
                "UPDATE role_assignments SET status = 'REVOKED'",
                "SELECT credential_secret_ref FROM linear_connections",
                "SELECT count(*) FROM migration_jobs",
                "SELECT content FROM migration_source_blobs",
                "SELECT token_hash FROM confirmation_secure_tokens",
                "SELECT key_version FROM confirmation_token_handoffs",
                "SELECT content FROM f05_private_artifact_blobs"
            }) {
                assertThrows(
                    java.sql.SQLException.class,
                    () -> statement.execute(forbidden),
                    forbidden);
            }
            assertThrows(
                java.sql.SQLException.class,
                () -> statement.execute(
                    "CREATE TABLE f07_worker_forbidden(id integer)"));
        }
        assertTrue(jdbc.queryForObject("""
            SELECT has_table_privilege(
                'vms_job_worker', 'f05_private_artifact_blobs', 'INSERT')
            """, Boolean.class));
        assertFalse(jdbc.queryForObject("""
            SELECT has_table_privilege(
                'vms_job_worker', 'f05_private_artifact_blobs', 'SELECT')
            """, Boolean.class));
        assertTrue(jdbc.queryForObject("""
            SELECT has_table_privilege(
                'vms_job_worker', 'f05_handoff_publish_attempts', 'INSERT')
            """, Boolean.class));
        assertTrue(jdbc.queryForObject("""
            SELECT has_table_privilege(
                'vms_job_worker', 'f05_domain_events', 'INSERT')
            """, Boolean.class));
        assertTrue(jdbc.queryForObject("""
            SELECT has_table_privilege(
                'vms_job_worker', 'engagement_months', 'SELECT')
            """, Boolean.class));
        assertTrue(jdbc.queryForObject("""
            SELECT has_column_privilege(
                'vms_job_worker', 'confirmation_token_handoffs',
                'encrypted_token', 'SELECT')
            """, Boolean.class));
        assertFalse(jdbc.queryForObject("""
            SELECT has_column_privilege(
                'vms_job_worker', 'confirmation_token_handoffs',
                'key_version', 'SELECT')
            """, Boolean.class));
    }

    @Test
    void deliveryCommitmentWorkerCanExecuteOnlyItsQueueCallGraph()
        throws Exception {
        UUID monthId = jdbc.queryForObject("""
            SELECT id FROM engagement_months ORDER BY id LIMIT 1
            """, UUID.class);
        UUID planId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID baselineId = UUID.randomUUID();
        UUID outboxId = UUID.randomUUID();
        try (Connection connection = jdbc.getDataSource().getConnection();
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("RESET ROLE");
            statement.execute("""
                INSERT INTO delivery_plans(
                    id, engagement_month_id, created_by_subject
                ) VALUES ('%s', '%s', 'f07-worker-test');
                INSERT INTO delivery_plan_versions(
                    id, plan_id, version, state, title, summary,
                    business_outcomes, coordinator_subject, baseline_type,
                    quorum_mode, quorum_required, checksum,
                    created_by_subject
                ) VALUES (
                    '%s', '%s', 1, 'FROZEN', 'Worker call graph',
                    'Worker call graph', 'Worker call graph',
                    'f07-worker-test', 'ON_TIME', 'ANY_ONE', 1,
                    '%s', 'f07-worker-test'
                );
                UPDATE delivery_plans
                SET current_version_id = '%s' WHERE id = '%s';
                INSERT INTO delivery_plan_baselines(
                    id, plan_version_id, checksum, deliverable_count
                ) VALUES ('%s', '%s', '%s', 0);
                INSERT INTO commitment_outbox(
                    id, plan_version_id, baseline_id, message_type,
                    idempotency_key, recipient_snapshot, subject_text,
                    plain_text, html_text, archive_reference
                ) VALUES (
                    '%s', '%s', '%s', 'INITIAL', 'f07-worker-%s',
                    '{}'::jsonb, 'subject', 'plain', '<p>html</p>',
                    'archive://worker-test'
                )
                """.formatted(
                planId, monthId, versionId, planId, "a".repeat(64),
                versionId, planId, baselineId, versionId, "a".repeat(64),
                outboxId, versionId, baselineId, outboxId));

            statement.execute("SET ROLE vms_job_worker");
            try (ResultSet row = statement.executeQuery("""
                SELECT plan_version_id, baseline_id, recipient_snapshot,
                       subject_text, plain_text, html_text, archive_reference
                FROM commitment_outbox
                WHERE id = '%s'
                """.formatted(outboxId))) {
                assertTrue(row.next());
            }
            assertEquals(1, statement.executeUpdate("""
                UPDATE commitment_outbox
                SET status = 'SENDING', lease_owner = 'worker-test',
                    lease_expires_at =
                        CURRENT_TIMESTAMP + INTERVAL '2 minutes',
                    attempt_count = attempt_count + 1
                WHERE id = '%s' AND status = 'PENDING'
                """.formatted(outboxId)));
            assertEquals(1, statement.executeUpdate("""
                UPDATE commitment_outbox
                SET status = 'SENT', provider_message_id = 'provider-message',
                    provider_thread_id = 'provider-thread',
                    sent_at = CURRENT_TIMESTAMP, next_attempt_at = NULL,
                    lease_owner = NULL, lease_expires_at = NULL,
                    last_error_code = NULL
                WHERE id = '%s' AND status = 'SENDING'
                  AND lease_owner = 'worker-test'
                """.formatted(outboxId)));
            assertEquals(1, statement.executeUpdate("""
                INSERT INTO commitment_outbox_attempts(
                    id, outbox_id, attempt_number, status,
                    provider_message_reference
                ) VALUES (
                    gen_random_uuid(), '%s', 1, 'SENT', 'provider-message'
                )
                """.formatted(outboxId)));
            assertThrows(java.sql.SQLException.class, () ->
                statement.execute("""
                    UPDATE commitment_outbox
                    SET subject_text = 'forbidden'
                    WHERE id = '%s'
                    """.formatted(outboxId)));
            connection.rollback();
        }
        assertTrue(jdbc.queryForObject("""
            SELECT has_table_privilege(
                'vms_job_worker', 'commitment_outbox', 'SELECT')
            """, Boolean.class));
        assertFalse(jdbc.queryForObject("""
            SELECT has_table_privilege(
                'vms_job_worker', 'commitment_outbox', 'INSERT')
            """, Boolean.class));
        assertFalse(jdbc.queryForObject("""
            SELECT has_table_privilege(
                'vms_job_worker', 'commitment_outbox', 'DELETE')
            """, Boolean.class));
        assertTrue(jdbc.queryForObject("""
            SELECT has_column_privilege(
                'vms_job_worker', 'commitment_outbox', 'status', 'UPDATE')
            """, Boolean.class));
        assertFalse(jdbc.queryForObject("""
            SELECT has_column_privilege(
                'vms_job_worker', 'commitment_outbox',
                'subject_text', 'UPDATE')
            """, Boolean.class));
        assertTrue(jdbc.queryForObject("""
            SELECT has_table_privilege(
                'vms_job_worker', 'commitment_outbox_attempts', 'INSERT')
            """, Boolean.class));
        assertFalse(jdbc.queryForObject("""
            SELECT has_table_privilege(
                'vms_job_worker', 'commitment_outbox_attempts', 'SELECT')
            """, Boolean.class));
        assertFalse(jdbc.queryForObject("""
            SELECT has_table_privilege(
                'vms_job_worker', 'commitment_outbox_attempts', 'UPDATE')
            """, Boolean.class));
    }

    @Test
    void publicHasNoBusinessPrivilegesAndFunctionsHaveFixedSearchPath() {
        assertEquals(0, jdbc.queryForObject("""
            SELECT count(*)
            FROM information_schema.role_table_grants
            WHERE grantee = 'PUBLIC'
              AND table_schema = 'public'
            """, Integer.class));
        assertEquals(0, jdbc.queryForObject("""
            SELECT count(*)
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
              AND NOT (
                  array_to_string(
                      COALESCE(procedure.proconfig, ARRAY[]::text[]), ',')
                  LIKE '%search_path=pg_catalog, public%'
              )
            """, Integer.class));
        assertEquals(1, jdbc.queryForObject("""
            SELECT count(*)
            FROM pg_proc procedure
            JOIN pg_namespace namespace ON namespace.oid = procedure.pronamespace
            WHERE namespace.nspname = 'public'
              AND procedure.prosecdef
              AND procedure.proname = 'f07_migration_leased_source'
              AND pg_get_userbyid(procedure.proowner)
                    = 'vms_migration_owner'
              AND array_to_string(
                    COALESCE(procedure.proconfig, ARRAY[]::text[]), ',')
                    LIKE '%search_path=pg_catalog, public%'
            """, Integer.class));
        assertEquals(0, jdbc.queryForObject("""
            SELECT count(*)
            FROM pg_proc procedure
            CROSS JOIN LATERAL aclexplode(
                COALESCE(
                    procedure.proacl,
                    acldefault('f', procedure.proowner))) privilege
            WHERE procedure.oid =
                    'f07_migration_leased_source(uuid,text)'::regprocedure
              AND privilege.grantee = 0
              AND privilege.privilege_type = 'EXECUTE'
            """, Integer.class));
        assertTrue(jdbc.queryForObject("""
            SELECT has_function_privilege(
                'vms_migration_processor',
                'f07_migration_leased_source(uuid,text)',
                'EXECUTE')
            """, Boolean.class));
        assertEquals(1, jdbc.queryForObject("""
            SELECT count(*)
            FROM pg_proc procedure
            JOIN pg_namespace namespace ON namespace.oid = procedure.pronamespace
            WHERE namespace.nspname = 'public'
              AND procedure.prosecdef
            """, Integer.class));
        assertEquals(0, jdbc.queryForObject("""
            SELECT count(*)
            FROM pg_roles
            WHERE rolname IN (
                'vms_migration_owner', 'vms_app_runtime', 'vms_reporting',
                'vms_job_worker', 'vms_migration_processor', 'vms_backup'
            )
              AND (
                rolcanlogin OR rolsuper OR rolcreatedb OR rolcreaterole
                OR NOT rolinherit OR rolreplication OR rolbypassrls
              )
            """, Integer.class));
    }

    @Test
    void newestSchemaTriggerFunctionsAreFixedAndMinimallyExecutable() {
        for (String function : new String[] {
            "delivery_commitment_outbox_content_guard()",
            "enforce_linear_reconciliation_terminal()",
            "enforce_linear_reconciliation_job_immutable()",
            "enforce_regularization_adjustment_scope()",
            "enforce_regularization_content_immutable()",
            "enforce_policy_command_organization()"
        }) {
            assertEquals(1, jdbc.queryForObject("""
                SELECT count(*)
                FROM pg_proc procedure
                JOIN pg_namespace namespace
                  ON namespace.oid = procedure.pronamespace
                WHERE procedure.oid = ?::regprocedure
                  AND namespace.nspname = 'public'
                  AND pg_get_userbyid(procedure.proowner)
                        = 'vms_migration_owner'
                  AND array_to_string(
                        COALESCE(procedure.proconfig, ARRAY[]::text[]), ',')
                        LIKE '%search_path=pg_catalog, public%'
                """, Integer.class, function), function);
            assertEquals(0, jdbc.queryForObject("""
                SELECT count(*)
                FROM pg_proc procedure
                CROSS JOIN LATERAL aclexplode(
                    COALESCE(
                        procedure.proacl,
                        acldefault('f', procedure.proowner))) privilege
                WHERE procedure.oid = ?::regprocedure
                  AND privilege.grantee = 0
                  AND privilege.privilege_type = 'EXECUTE'
                """, Integer.class, function), function);
            assertTrue(jdbc.queryForObject("""
                SELECT has_function_privilege(
                    'vms_app_runtime', ?::regprocedure, 'EXECUTE')
                """, Boolean.class, function), function);
        }
    }

    @Test
    void runtimePolicyCommandInsertEnforcesOrganizationLineage()
        throws Exception {
        UUID foreignCalendar = UUID.randomUUID();
        UUID foreignLeaveType = UUID.randomUUID();
        UUID validCommand = UUID.randomUUID();
        try (Connection connection = jdbc.getDataSource().getConnection();
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("SET ROLE vms_app_runtime");
            statement.execute("""
                INSERT INTO working_calendar_versions
                    (id, organization_id, name, timezone, version, valid_from,
                     expected_full_minutes, expected_half_minutes)
                VALUES (
                    '%s', '00000000-0000-0000-0000-000000000102',
                    'Runtime foreign policy calendar', 'UTC', 1,
                    DATE '2026-01-01', 540, 270)
                """.formatted(foreignCalendar));
            statement.execute("""
                INSERT INTO leave_types
                    (id, organization_id, code, name, paid, balance_tracked,
                     minimum_increment)
                VALUES (
                    '%s', '00000000-0000-0000-0000-000000000102',
                    'RUNTIME_FOREIGN', 'Runtime foreign leave',
                    TRUE, TRUE, 0.5)
                """.formatted(foreignLeaveType));

            Savepoint crossOrganization = connection.setSavepoint();
            assertThrows(java.sql.SQLException.class, () ->
                statement.execute("""
                    INSERT INTO employee_policy_assignment_commands
                        (id, employee_id, calendar_version_id, leave_type_id,
                         opening_units, effective_from, idempotency_key,
                         reason, created_by_subject)
                    VALUES (
                        gen_random_uuid(),
                        '00000000-0000-0000-0000-000000000801',
                        '%s', '%s', 1, DATE '2026-07-01',
                        'runtime-cross-org-policy',
                        'Cross-organization lineage must fail',
                        'SYSTEM:RUNTIME_ROLE_TEST')
                    """.formatted(foreignCalendar, foreignLeaveType)));
            connection.rollback(crossOrganization);

            assertEquals(1, statement.executeUpdate("""
                INSERT INTO employee_policy_assignment_commands
                    (id, employee_id, calendar_version_id, leave_type_id,
                     opening_units, effective_from, idempotency_key,
                     reason, created_by_subject)
                VALUES (
                    '%s', '00000000-0000-0000-0000-000000000801',
                    '00000000-0000-0000-0000-000000000901',
                    '00000000-0000-0000-0000-000000000921',
                    1, DATE '2026-07-01', 'runtime-valid-policy',
                    'Same-organization lineage is valid',
                    'SYSTEM:RUNTIME_ROLE_TEST')
                """.formatted(validCommand)));
            try (ResultSet rows = statement.executeQuery("""
                SELECT organization_id::text
                FROM employee_policy_assignment_commands
                WHERE id = '%s'
                """.formatted(validCommand))) {
                assertTrue(rows.next());
                assertEquals(
                    "00000000-0000-0000-0000-000000000101",
                    rows.getString(1));
            }
            connection.rollback();
        }
    }

    @Test
    void migrationCapabilityIsNoLoginAndRuntimeCannotAssumeIt() {
        assertTrue(jdbc.queryForObject("""
            SELECT has_schema_privilege(
                'vms_migration_owner', 'public', 'CREATE')
            """, Boolean.class));
        assertFalse(jdbc.queryForObject("""
            SELECT rolcanlogin FROM pg_roles
            WHERE rolname = 'vms_migration_owner'
            """, Boolean.class));
        assertFalse(jdbc.queryForObject("""
            SELECT has_schema_privilege(
                'vms_app_runtime', 'public', 'CREATE')
            """, Boolean.class));
        assertFalse(jdbc.queryForObject("""
            SELECT pg_has_role(
                'vms_app_runtime', 'vms_migration_owner', 'MEMBER')
            """, Boolean.class));
    }

    @Test
    void migrationProcessorCanOnlyReadSourceThroughItsLiveLease()
        throws Exception {
        UUID sourceId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID engagementId = jdbc.queryForObject(
            "SELECT id FROM engagements ORDER BY id LIMIT 1", UUID.class);
        UUID organizationId = jdbc.queryForObject("""
            SELECT vendor_organization_id
            FROM engagements WHERE id = ?
            """, UUID.class, engagementId);
        jdbc.update("""
            INSERT INTO migration_source_files(
                id, engagement_id, organization_id, template_code,
                template_version, safe_filename, media_type, byte_size,
                sha256, scan_status, uploaded_by_subject, retention_until
            ) VALUES (?, ?, ?, '01_employees', '1', 'lease.csv',
                      'text/csv', 4, repeat('a', 64), 'PENDING',
                      'test-owner', CURRENT_DATE + 1)
            """, sourceId, engagementId, organizationId);
        jdbc.update("""
            INSERT INTO migration_source_blobs(source_file_id, content)
            VALUES (?, ?)
            """, sourceId, "safe".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        jdbc.update("""
            INSERT INTO migration_jobs(
                id, source_file_id, engagement_id, organization_id,
                template_code, template_version, mode, state,
                requested_by_subject, lease_owner, lease_until
            ) VALUES (?, ?, ?, ?, '01_employees', '1', 'DRY_RUN',
                      'FAILED', 'test-owner', 'lease-a',
                      CURRENT_TIMESTAMP + INTERVAL '5 minutes')
            """, jobId, sourceId, engagementId, organizationId);

        try (Connection connection = jdbc.getDataSource().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE vms_migration_processor");
            assertThrows(
                java.sql.SQLException.class,
                () -> statement.execute(
                    "SELECT content FROM migration_source_blobs"));
            try (ResultSet row = statement.executeQuery("""
                SELECT encode(
                    f07_migration_leased_source(
                        '%s', 'lease-a'), 'escape')
                """.formatted(jobId))) {
                assertTrue(row.next());
                assertEquals("safe", row.getString(1));
            }
            try (ResultSet row = statement.executeQuery("""
                SELECT f07_migration_leased_source(
                    '%s', 'wrong-lease') IS NULL
                """.formatted(jobId))) {
                assertTrue(row.next());
                assertTrue(row.getBoolean(1));
            }
            for (String forbidden : new String[] {
                "SELECT email FROM user_profiles",
                "SELECT count(*) FROM role_assignments",
                "SELECT credential_secret_ref FROM linear_connections",
                "INSERT INTO migration_outbox_events("
                    + "id, job_id, event_type, aggregate_id, payload"
                    + ") VALUES (gen_random_uuid(), '" + jobId
                    + "', 'DENIED', gen_random_uuid(), '{}'::jsonb)",
                "CREATE TABLE f07_migration_processor_forbidden(id integer)"
            }) {
                assertThrows(
                    java.sql.SQLException.class,
                    () -> statement.execute(forbidden),
                    forbidden);
            }
        }
    }
}
