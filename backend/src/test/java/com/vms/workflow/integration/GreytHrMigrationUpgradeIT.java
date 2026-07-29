package com.vms.workflow.integration;

import com.vms.workflow.integration.support.VmsPostgreSqlContainerProvider;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GreytHrMigrationUpgradeIT {
    @Test
    void populatedV25UpgradesToV26WithoutLosingFactsAndWithHardenedRoles()
        throws Exception {
        try (PostgreSQLContainer<?> postgres = postgres("greythr_upgrade")) {
            postgres.start();
            migrateWithDeadlockRetry(Flyway.configure()
                .dataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())
                .locations("classpath:db/migration")
                .target("25")
                .load());

            UUID organizationId = UUID.randomUUID();
            UUID employeeId = UUID.randomUUID();
            UUID connectionId = UUID.randomUUID();
            UUID runId = UUID.randomUUID();
            UUID factId = UUID.randomUUID();
            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword());
                 Statement statement = connection.createStatement()) {
                statement.execute("""
                    INSERT INTO organizations(
                        id, code, legal_name, display_name,
                        organization_type, status
                    ) VALUES (
                        '%s', 'UPGRADE', 'Upgrade tenant', 'Upgrade tenant',
                        'OTHER', 'ACTIVE'
                    )
                    """.formatted(organizationId));
                statement.execute("""
                    INSERT INTO employees(
                        id, organization_id, employee_number, work_email,
                        join_date, created_by_subject
                    ) VALUES (
                        '%s', '%s', 'UP-001', 'up-001@example.test',
                        CURRENT_DATE, 'upgrade-test'
                    )
                    """.formatted(employeeId, organizationId));
                statement.execute("""
                    INSERT INTO greythr_connections(
                        id, organization_id, display_name, status,
                        adapter_mode, created_by_subject
                    ) VALUES (
                        '%s', '%s', 'Upgrade connection', 'DISCOVERED',
                        'RECORDED_FIXTURE', 'upgrade-test'
                    )
                    """.formatted(connectionId, organizationId));
                statement.execute("""
                    INSERT INTO greythr_sync_runs(
                        id, connection_id, organization_id, idempotency_key,
                        request_hash, date_from, date_to, status,
                        correlation_id
                    ) VALUES (
                        '%s', '%s', '%s', 'upgrade-run', '%s',
                        CURRENT_DATE, CURRENT_DATE, 'RUNNING', '%s'
                    )
                    """.formatted(
                    runId, connectionId, organizationId, "a".repeat(64),
                    UUID.randomUUID()));
                statement.execute("""
                    INSERT INTO greythr_imported_facts(
                        id, connection_id, sync_run_id, employee_id,
                        provider_employee_id, fact_kind, provider_record_id,
                        payload_hash, source_updated_at, payload
                    ) VALUES (
                        '%s', '%s', '%s', '%s', 'GHR-UP-001', 'EMPLOYEE',
                        'employee-up-001-v1', '%s', CURRENT_TIMESTAMP,
                        '{
                          "providerRecordId":"employee-up-001-v1",
                          "providerEmployeeId":"GHR-UP-001",
                          "employeeNumber":"UP-001"
                        }'::jsonb
                    )
                    """.formatted(
                    factId, connectionId, runId, employeeId,
                    "b".repeat(64)));
            }

            migrateWithDeadlockRetry(Flyway.configure()
                .dataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())
                .locations("classpath:db/migration")
                .target("26")
                .load());

            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword());
                 Statement statement = connection.createStatement()) {
                assertEquals("1", scalar(statement, """
                    SELECT count(*)::text
                    FROM greythr_imported_facts
                    WHERE id = '%s'
                      AND organization_id = '%s'
                    """.formatted(factId, organizationId)));
                assertEquals("1", scalar(statement, """
                    SELECT count(*)::text
                    FROM greythr_employee_mappings
                    WHERE source_fact_id = '%s'
                      AND mapping_version = 1
                    """.formatted(factId)));
                assertEquals("vms_migration_owner", scalar(statement, """
                    SELECT tableowner
                    FROM pg_tables
                    WHERE schemaname = 'public'
                      AND tablename = 'greythr_imported_facts'
                    """));
                assertEquals("O", scalar(statement, """
                    SELECT tgenabled
                    FROM pg_trigger
                    WHERE tgrelid = 'greythr_imported_facts'::regclass
                      AND tgname = 'greythr_imported_facts_immutable'
                    """));
                statement.execute("SET ROLE vms_app_runtime");
                assertThrows(java.sql.SQLException.class, () ->
                    statement.execute("""
                        UPDATE greythr_employee_mappings
                        SET mapping_version = 2
                        WHERE source_fact_id = '%s'
                        """.formatted(factId)));
            }
        }
    }

    @Test
    void failedV26UpgradeRollsBackBackfillAndLeavesV25TriggerEnabled()
        throws Exception {
        try (PostgreSQLContainer<?> postgres =
                 postgres("greythr_upgrade_rollback")) {
            postgres.start();
            migrateWithDeadlockRetry(Flyway.configure()
                .dataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())
                .locations("classpath:db/migration")
                .target("25")
                .load());

            UUID organizationId = UUID.randomUUID();
            UUID employeeId = UUID.randomUUID();
            UUID connectionId = UUID.randomUUID();
            UUID runId = UUID.randomUUID();
            UUID factId = UUID.randomUUID();
            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword());
                 Statement statement = connection.createStatement()) {
                statement.execute("""
                    WITH inserted_organization AS (
                        INSERT INTO organizations(
                            id, code, legal_name, display_name,
                            organization_type, status
                        ) VALUES (
                            '%s', 'ROLLBACK', 'Rollback tenant',
                            'Rollback tenant', 'OTHER', 'ACTIVE'
                        )
                    ), inserted_employee AS (
                        INSERT INTO employees(
                            id, organization_id, employee_number, work_email,
                            join_date, created_by_subject
                        ) VALUES (
                            '%s', '%s', 'RB-001', 'rb-001@example.test',
                            CURRENT_DATE, 'upgrade-test'
                        )
                    ), inserted_connection AS (
                        INSERT INTO greythr_connections(
                            id, organization_id, display_name, status,
                            adapter_mode, created_by_subject
                        ) VALUES (
                            '%s', '%s', 'Rollback connection', 'DISCOVERED',
                            'RECORDED_FIXTURE', 'upgrade-test'
                        )
                    ), inserted_run AS (
                        INSERT INTO greythr_sync_runs(
                            id, connection_id, organization_id,
                            idempotency_key, request_hash, date_from, date_to,
                            status, correlation_id
                        ) VALUES (
                            '%s', '%s', '%s', 'rollback-run', '%s',
                            CURRENT_DATE, CURRENT_DATE, 'RUNNING', '%s'
                        )
                    )
                    INSERT INTO greythr_imported_facts(
                        id, connection_id, sync_run_id, employee_id,
                        provider_employee_id, fact_kind, provider_record_id,
                        payload_hash, source_updated_at, payload
                    ) VALUES (
                        '%s', '%s', '%s', '%s', 'GHR-RB-001', 'EMPLOYEE',
                        'employee-rb-001-v1', '%s', CURRENT_TIMESTAMP,
                        '{"providerRecordId":"employee-rb-001-v1",
                          "providerEmployeeId":"GHR-RB-001",
                          "employeeNumber":"RB-001","salary":100}'::jsonb
                    )
                    """.formatted(
                    organizationId, employeeId, organizationId,
                    connectionId, organizationId, runId, connectionId,
                    organizationId, "c".repeat(64), UUID.randomUUID(),
                    factId, connectionId, runId, employeeId, "d".repeat(64)));
            }

            Flyway upgrade = Flyway.configure()
                .dataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())
                .locations("classpath:db/migration")
                .target("26")
                .load();
            assertThrows(FlywayException.class, () ->
                migrateWithDeadlockRetry(upgrade));

            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword());
                 Statement statement = connection.createStatement()) {
                assertEquals("25", scalar(statement, """
                    SELECT version
                    FROM flyway_schema_history
                    WHERE success
                    ORDER BY installed_rank DESC
                    LIMIT 1
                    """));
                assertEquals("O", scalar(statement, """
                    SELECT tgenabled
                    FROM pg_trigger
                    WHERE tgrelid = 'greythr_imported_facts'::regclass
                      AND tgname = 'greythr_imported_facts_immutable'
                    """));
                assertEquals("1", scalar(statement, """
                    SELECT count(*)::text FROM greythr_imported_facts
                    WHERE id = '%s'
                    """.formatted(factId)));
                assertEquals("0", scalar(statement, """
                    SELECT count(*)::text
                    FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name = 'greythr_imported_facts'
                      AND column_name = 'organization_id'
                    """));
            }
        }
    }

    @Test
    void v25CapabilityStatesUpgradeWithCertifiedOnlyEvidenceAndSafeAudit()
        throws Exception {
        try (PostgreSQLContainer<?> postgres =
                 postgres("greythr_capability_upgrade")) {
            postgres.start();
            migrateWithDeadlockRetry(Flyway.configure()
                .dataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())
                .locations("classpath:db/migration")
                .target("25")
                .load());

            UUID organizationId = UUID.randomUUID();
            UUID certifiedConnection = UUID.randomUUID();
            UUID draftConnection = UUID.randomUUID();
            UUID revokedConnection = UUID.randomUUID();
            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword());
                 Statement statement = connection.createStatement()) {
                statement.execute("""
                    INSERT INTO organizations(
                        id, code, legal_name, display_name,
                        organization_type, status
                    ) VALUES (
                        '%s', 'CAPUP', 'Capability upgrade',
                        'Capability upgrade', 'OTHER', 'ACTIVE'
                    )
                    """.formatted(organizationId));
                insertV25CapabilityConnection(
                    statement, organizationId, certifiedConnection,
                    "CERTIFIED");
                insertV25CapabilityConnection(
                    statement, organizationId, draftConnection, "DRAFT");
                insertV25CapabilityConnection(
                    statement, organizationId, revokedConnection, "REVOKED");
            }

            migrateWithDeadlockRetry(Flyway.configure()
                .dataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())
                .locations("classpath:db/migration")
                .target("26")
                .load());

            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword());
                 Statement statement = connection.createStatement()) {
                assertEquals("ACTIVE", scalar(statement, """
                    SELECT status FROM greythr_connections
                    WHERE id = '%s'
                    """.formatted(certifiedConnection)));
                assertEquals("1", scalar(statement, """
                    SELECT count(*)::text
                    FROM greythr_certification_evidence
                    WHERE connection_id = '%s'
                      AND certification_status = 'CERTIFIED'
                    """.formatted(certifiedConnection)));
                assertEquals("2", scalar(statement, """
                    SELECT count(*)::text
                    FROM greythr_certification_upgrade_audit
                    WHERE reason_code =
                        'CAPABILITY_CERTIFICATION_NOT_CERTIFIED'
                    """));
                for (UUID invalid : new UUID[]{
                    draftConnection, revokedConnection
                }) {
                    assertEquals("DISCOVERED", scalar(statement, """
                        SELECT status FROM greythr_connections
                        WHERE id = '%s'
                          AND capability_certification_id IS NULL
                          AND last_error_code =
                              'CAPABILITY_CERTIFICATION_NOT_CERTIFIED'
                        """.formatted(invalid)));
                }
                assertEquals("0", scalar(statement, """
                    SELECT count(*)::text
                    FROM greythr_certification_evidence
                    WHERE connection_id IN ('%s', '%s')
                    """.formatted(draftConnection, revokedConnection)));
            }
        }
    }

    @Test
    void v29LegacyCertifiedConnectionUpgradesToV30FailClosedWithoutDataLoss()
        throws Exception {
        try (PostgreSQLContainer<?> postgres =
                 postgres("greythr_attestation_upgrade")) {
            postgres.start();
            migrateWithDeadlockRetry(Flyway.configure()
                .dataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())
                .locations("classpath:db/migration")
                .target("29")
                .load());

            UUID organizationId = UUID.randomUUID();
            UUID connectionId = UUID.randomUUID();
            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword());
                 Statement statement = connection.createStatement()) {
                statement.execute("""
                    INSERT INTO organizations(
                        id, code, legal_name, display_name,
                        organization_type, status
                    ) VALUES (
                        '%s', 'ATTUP', 'Attestation upgrade',
                        'Attestation upgrade', 'OTHER', 'ACTIVE'
                    )
                    """.formatted(organizationId));
                UUID certificationId = UUID.randomUUID();
                statement.execute("""
                    INSERT INTO integration_capability_certifications(
                        id, organization_id, provider, status, certified_at,
                        capability_manifest
                    ) VALUES (
                        '%s', '%s', 'GREYTHR', 'CERTIFIED',
                        CURRENT_TIMESTAMP,
                        '{"schema":"greythr-capability-v1",
                          "capabilities":[
                            "EMPLOYEES","ATTENDANCE","LEAVE"
                          ]}'::jsonb
                    );
                    INSERT INTO greythr_connections(
                        id, organization_id, display_name, status,
                        adapter_mode, created_by_subject
                    ) VALUES (
                        '%s', '%s', 'Legacy attestation', 'DISCOVERED',
                        'RECORDED_FIXTURE', 'upgrade-test'
                    );
                    UPDATE greythr_connections
                    SET capability_certification_id = '%s', status = 'ACTIVE'
                    WHERE id = '%s'
                    """.formatted(
                    certificationId, organizationId, connectionId,
                    organizationId, certificationId, connectionId));
            }

            migrateWithDeadlockRetry(Flyway.configure()
                .dataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())
                .locations("classpath:db/migration")
                .target("30")
                .load());

            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword());
                 Statement statement = connection.createStatement()) {
                assertEquals("DISCOVERED", scalar(statement, """
                    SELECT status FROM greythr_connections
                    WHERE id = '%s'
                      AND capability_certification_id IS NULL
                      AND last_error_code = 'PROVIDER_ATTESTATION_REQUIRED'
                    """.formatted(connectionId)));
                assertEquals("1", scalar(statement, """
                    SELECT count(*)::text
                    FROM greythr_certification_upgrade_audit
                    WHERE connection_id = '%s'
                      AND reason_code = 'PROVIDER_ATTESTATION_REQUIRED'
                    """.formatted(connectionId)));
                assertEquals("1", scalar(statement, """
                    SELECT count(*)::text
                    FROM greythr_certification_evidence
                    WHERE connection_id = '%s'
                      AND provider_probe_evidence_id IS NULL
                    """.formatted(connectionId)));
                assertEquals("1", scalar(statement, """
                    SELECT count(*)::text
                    FROM information_schema.tables
                    WHERE table_schema = 'public'
                      AND table_name =
                          'greythr_capability_probe_evidence'
                    """));
            }
        }
    }

    private void insertV25CapabilityConnection(
        Statement statement,
        UUID organizationId,
        UUID connectionId,
        String certificationStatus
    ) throws Exception {
        UUID certificationId = UUID.randomUUID();
        String certifiedAt = "CERTIFIED".equals(certificationStatus)
            ? "CURRENT_TIMESTAMP" : "NULL";
        statement.execute("""
            INSERT INTO integration_capability_certifications(
                id, organization_id, provider, status, certified_at,
                capability_manifest
            ) VALUES (
                '%s', '%s', 'GREYTHR', '%s', %s,
                '{"capabilities":[
                  "EMPLOYEES","ATTENDANCE","LEAVE"
                ]}'::jsonb
            );
            INSERT INTO greythr_connections(
                id, organization_id, display_name, status, adapter_mode,
                capability_certification_id, created_by_subject
            ) VALUES (
                '%s', '%s', '%s connection', 'ACTIVE',
                'RECORDED_FIXTURE', '%s', 'upgrade-test'
            )
            """.formatted(
            certificationId, organizationId, certificationStatus,
            certifiedAt, connectionId, organizationId,
            certificationStatus, certificationId));
    }

    private PostgreSQLContainer<?> postgres(String databaseName) {
        return new PostgreSQLContainer<>(
            DockerImageName.parse(
                "cgr.dev/chainguard/postgres@sha256:"
                    + "dc2f04037c1044a22af76cee4de70b9111885b17c561b93"
                    + "9d7ed70103d100759")
                .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName(databaseName)
            .withUsername("upgrade_admin")
            .withPassword("upgrade-password")
            .withCommand("-c", "fsync=off")
            .withTmpFs(Map.of(
                VmsPostgreSqlContainerProvider.POSTGRES_DATA_DIRECTORY,
                VmsPostgreSqlContainerProvider.POSTGRES_TMPFS_OPTIONS))
            .waitingFor(Wait.forLogMessage(
                    ".*database system is ready to accept connections.*\\s", 2)
                .withStartupTimeout(Duration.ofMinutes(3)));
    }

    private void migrateWithDeadlockRetry(Flyway flyway) {
        FlywayException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                flyway.migrate();
                return;
            } catch (FlywayException exception) {
                last = exception;
                if (!exception.getMessage().contains("deadlock detected")
                    || attempt == 3) {
                    throw exception;
                }
            }
        }
        throw last;
    }

    private String scalar(Statement statement, String sql) throws Exception {
        try (ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }
}
