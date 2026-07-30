package com.vms.workflow.integration;

import com.vms.workflow.integration.support.VmsPostgreSqlContainerProvider;
import org.flywaydb.core.Flyway;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinanceRetentionMigrationUpgradeIT {
    @Test
    void populatedV44UpgradesToV45WithoutInventingRetentionSchedules()
        throws Exception {
        try (PostgreSQLContainer<?> postgres = postgres()) {
            postgres.start();
            Flyway.configure()
                .dataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())
                .locations("classpath:db/migration")
                .target("44")
                .load()
                .migrate();
            Flyway.configure()
                .dataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword());
                 Statement statement = connection.createStatement()) {
                assertEquals("45", scalar(statement, """
                    SELECT version FROM flyway_schema_history
                    WHERE success ORDER BY installed_rank DESC LIMIT 1
                    """));
                assertEquals("0", scalar(statement, """
                    SELECT count(*)::text FROM f07_retention_schedules
                    WHERE record_class IN (
                        'FINANCE_EXPORT_CONTENT',
                        'FINANCE_EVIDENCE_CONTENT'
                    )
                    """));
                assertEquals("2", scalar(statement, """
                    SELECT count(*)::text
                    FROM f07_data_classification_inventory
                    WHERE asset_name IN (
                        'f05_private_artifacts',
                        'f05_private_artifact_blobs'
                    )
                      AND retention_record_class =
                          'FINANCE_EVIDENCE_CONTENT'
                    """));
                assertEquals("1", scalar(statement, """
                    SELECT count(*)::text
                    FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name = 'f05_private_artifacts'
                      AND column_name = 'retention_status'
                    """));
            }
        }
    }

    private PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>(
            DockerImageName.parse(
                "cgr.dev/chainguard/postgres@sha256:"
                    + "dc2f04037c1044a22af76cee4de70b9111885b17c561b93"
                    + "9d7ed70103d100759")
                .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("finance_retention_v45_upgrade")
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

    private String scalar(Statement statement, String sql) throws Exception {
        try (ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }
}
