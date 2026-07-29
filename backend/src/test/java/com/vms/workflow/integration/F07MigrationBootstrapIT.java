package com.vms.workflow.integration;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the production handoff instead of relying on the Testcontainers
 * superuser used by Spring integration tests: V1-V20 are created by a
 * constrained migration login, V21 transfers ownership to the NOLOGIN
 * capability role, and later migrations continue with no SET ROLE.
 */
class F07MigrationBootstrapIT {
    @Test
    void constrainedMigrationLoginCanUpgradeFreshDatabaseThroughV24()
        throws Exception {
        try (PostgreSQLContainer<?> postgres =
                 new PostgreSQLContainer<>(
                     DockerImageName.parse(
                         "cgr.dev/chainguard/postgres@sha256:"
                             + "dc2f04037c1044a22af76cee4de70b9111885b17c561b93"
                             + "9d7ed70103d100759")
                         .asCompatibleSubstituteFor("postgres"))
                     .withDatabaseName("f07_bootstrap")
                     .withUsername("bootstrap_admin")
                     .withPassword("bootstrap-admin-password")
                     .withCommand("-c", "fsync=off")
                     .waitingFor(Wait.forLogMessage(
                             ".*database system is ready to accept connections.*\\s",
                             2)
                         .withStartupTimeout(Duration.ofMinutes(3)))) {
            postgres.start();
            String migrationLogin = "f07_migration_login";
            String migrationPassword = "migration-password";
            try (Connection admin = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword());
                 Statement statement = admin.createStatement()) {
                statement.execute("""
                    CREATE ROLE f07_migration_login
                    LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT
                    NOREPLICATION NOBYPASSRLS
                    PASSWORD 'migration-password'
                    """);
                for (String capability : new String[]{
                    "vms_migration_owner", "vms_app_runtime",
                    "vms_reporting", "vms_job_worker",
                    "vms_migration_processor", "vms_backup"
                }) {
                    statement.execute("CREATE ROLE " + capability
                        + " NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE "
                        + "INHERIT NOREPLICATION NOBYPASSRLS");
                }
                statement.execute(
                    "GRANT vms_migration_owner TO " + migrationLogin);
                statement.execute(
                    "ALTER DATABASE f07_bootstrap OWNER TO " + migrationLogin);
                statement.execute(
                    "ALTER SCHEMA public OWNER TO " + migrationLogin);
            }

            Flyway flyway = Flyway.configure()
                .dataSource(
                    postgres.getJdbcUrl(), migrationLogin, migrationPassword)
                .locations("classpath:db/migration")
                .load();
            assertTrue(flyway.migrate().migrationsExecuted > 0);

            try (Connection migration = DriverManager.getConnection(
                    postgres.getJdbcUrl(), migrationLogin, migrationPassword);
                 Statement statement = migration.createStatement()) {
                assertTrue(Integer.parseInt(scalar(statement, """
                    SELECT version FROM flyway_schema_history
                    WHERE success ORDER BY installed_rank DESC LIMIT 1
                    """)) >= 24);
                assertEquals("vms_migration_owner", scalar(statement, """
                    SELECT tableowner FROM pg_tables
                    WHERE schemaname = 'public'
                      AND tablename = 'f07_feature_flags'
                    """));
                assertEquals(migrationLogin, scalar(statement, """
                    SELECT tableowner FROM pg_tables
                    WHERE schemaname = 'public'
                      AND tablename = 'flyway_schema_history'
                    """));
                assertEquals("true", scalar(statement, """
                    SELECT pg_has_role(
                        current_user, 'vms_migration_owner', 'MEMBER')::text
                    """));
                assertFalse(Boolean.parseBoolean(scalar(statement, """
                    SELECT rolsuper::text FROM pg_roles
                    WHERE rolname = current_user
                    """)));
                assertEquals("0", scalar(statement, """
                    SELECT count(*)::text
                    FROM pg_default_acl defaults
                    JOIN pg_roles owner ON owner.oid = defaults.defaclrole
                    JOIN pg_namespace namespace
                      ON namespace.oid = defaults.defaclnamespace
                    CROSS JOIN LATERAL aclexplode(defaults.defaclacl) access
                    WHERE owner.rolname = current_user
                      AND namespace.nspname = 'public'
                      AND access.grantee = 0
                    """));
            }
        }
    }

    private String scalar(Statement statement, String sql) throws Exception {
        try (ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }
}
