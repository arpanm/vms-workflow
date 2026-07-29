package com.vms.workflow.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Production startup proof that the application pool is not using the Flyway
 * owner (or another DDL-capable principal).
 */
@Component
public class DatabaseRoleGuard implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final String expectedRole;
    private final String expectedCapabilityRole;

    public DatabaseRoleGuard(
        JdbcTemplate jdbc,
        @Value("${vms.database.verify-runtime-least-privilege:false}")
        boolean enabled,
        @Value("${vms.database.expected-runtime-role:}")
        String expectedRole,
        @Value("${vms.database.expected-capability-role:}")
        String expectedCapabilityRole
    ) {
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.expectedRole = expectedRole == null ? "" : expectedRole.strip();
        this.expectedCapabilityRole = expectedCapabilityRole == null
            ? "" : expectedCapabilityRole.strip();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        if (expectedRole.isEmpty() || expectedCapabilityRole.isEmpty()) {
            throw new IllegalStateException(
                "Runtime database role verification requires an exact login and capability role.");
        }
        String actualRole =
            jdbc.queryForObject("SELECT current_user", String.class);
        Boolean schemaCreate = jdbc.queryForObject(
            "SELECT has_schema_privilege(current_user, 'public', 'CREATE')",
            Boolean.class);
        Boolean flywayMutation = jdbc.queryForObject(
            """
            SELECT has_table_privilege(
                current_user, 'public.flyway_schema_history', 'UPDATE')
            """,
            Boolean.class);
        Boolean migrationMembership = jdbc.queryForObject(
            """
            SELECT pg_has_role(
                current_user, 'vms_migration_owner', 'MEMBER')
            """,
            Boolean.class);
        Boolean expectedMembership = jdbc.queryForObject(
            "SELECT pg_has_role(current_user, ?, 'MEMBER')",
            Boolean.class, expectedCapabilityRole);
        Integer unexpectedCapabilities = jdbc.queryForObject("""
            SELECT count(*)
            FROM pg_roles capability
            WHERE capability.rolname IN (
                'vms_app_runtime', 'vms_reporting', 'vms_job_worker',
                'vms_migration_processor', 'vms_backup')
              AND capability.rolname <> ?
              AND pg_has_role(
                    current_user, capability.oid, 'MEMBER')
            """, Integer.class, expectedCapabilityRole);
        Boolean safeLoginAttributes = jdbc.queryForObject("""
            SELECT rolcanlogin
                   AND NOT rolsuper
                   AND NOT rolcreatedb
                   AND NOT rolcreaterole
                   AND rolinherit
                   AND NOT rolreplication
                   AND NOT rolbypassrls
            FROM pg_roles
            WHERE rolname = current_user
            """, Boolean.class);
        if (!expectedRole.equals(actualRole)
            || Boolean.TRUE.equals(schemaCreate)
            || Boolean.TRUE.equals(flywayMutation)
            || Boolean.TRUE.equals(migrationMembership)
            || !Boolean.TRUE.equals(expectedMembership)
            || unexpectedCapabilities == null
            || unexpectedCapabilities != 0
            || !Boolean.TRUE.equals(safeLoginAttributes)) {
            throw new IllegalStateException(
                "Runtime database principal does not satisfy the least-privilege policy.");
        }
    }
}
