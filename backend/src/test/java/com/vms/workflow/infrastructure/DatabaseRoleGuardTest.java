package com.vms.workflow.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseRoleGuardTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    @Test
    void acceptsExactRuntimeRoleWithoutDdlOrFlywayMutation() {
        when(jdbc.queryForObject("SELECT current_user", String.class))
            .thenReturn("vms_app_login");
        when(jdbc.queryForObject(
            "SELECT has_schema_privilege(current_user, 'public', 'CREATE')",
            Boolean.class)).thenReturn(false);
        when(jdbc.queryForObject(
            """
            SELECT has_table_privilege(
                current_user, 'public.flyway_schema_history', 'UPDATE')
            """,
            Boolean.class)).thenReturn(false);
        when(jdbc.queryForObject(
            """
            SELECT pg_has_role(
                current_user, 'vms_migration_owner', 'MEMBER')
            """,
            Boolean.class)).thenReturn(false);
        when(jdbc.queryForObject(
            "SELECT pg_has_role(current_user, ?, 'MEMBER')",
            Boolean.class, "vms_app_runtime")).thenReturn(true);
        when(jdbc.queryForObject(
            """
            SELECT count(*)
            FROM pg_roles capability
            WHERE capability.rolname IN (
                'vms_app_runtime', 'vms_reporting', 'vms_job_worker',
                'vms_migration_processor', 'vms_backup')
              AND capability.rolname <> ?
              AND pg_has_role(
                    current_user, capability.oid, 'MEMBER')
            """, Integer.class, "vms_app_runtime")).thenReturn(0);
        when(jdbc.queryForObject(
            """
            SELECT rolcanlogin
                   AND NOT rolsuper
                   AND NOT rolcreatedb
                   AND NOT rolcreaterole
                   AND rolinherit
                   AND NOT rolreplication
                   AND NOT rolbypassrls
            FROM pg_roles
            WHERE rolname = current_user
            """, Boolean.class)).thenReturn(true);

        assertDoesNotThrow(() ->
            new DatabaseRoleGuard(
                jdbc, true, "vms_app_login", "vms_app_runtime").run(null));
    }

    @Test
    void rejectsMigrationCapableOrUnexpectedPrincipal() {
        when(jdbc.queryForObject("SELECT current_user", String.class))
            .thenReturn("migration_owner_login");
        when(jdbc.queryForObject(
            "SELECT has_schema_privilege(current_user, 'public', 'CREATE')",
            Boolean.class)).thenReturn(true);
        when(jdbc.queryForObject(
            """
            SELECT has_table_privilege(
                current_user, 'public.flyway_schema_history', 'UPDATE')
            """,
            Boolean.class)).thenReturn(true);
        when(jdbc.queryForObject(
            """
            SELECT pg_has_role(
                current_user, 'vms_migration_owner', 'MEMBER')
            """,
            Boolean.class)).thenReturn(true);

        assertThrows(IllegalStateException.class, () ->
            new DatabaseRoleGuard(
                jdbc, true, "vms_app_login", "vms_app_runtime").run(null));
    }

    @Test
    void failsClosedWhenEnabledWithoutExpectedRole() {
        assertThrows(IllegalStateException.class, () ->
            new DatabaseRoleGuard(jdbc, true, " ", " ").run(null));
    }

    @Test
    void rejectsLoginWithUnsafeClusterAttributes() {
        when(jdbc.queryForObject("SELECT current_user", String.class))
            .thenReturn("vms_worker_login");
        when(jdbc.queryForObject(
            "SELECT has_schema_privilege(current_user, 'public', 'CREATE')",
            Boolean.class)).thenReturn(false);
        when(jdbc.queryForObject(
            """
            SELECT has_table_privilege(
                current_user, 'public.flyway_schema_history', 'UPDATE')
            """, Boolean.class)).thenReturn(false);
        when(jdbc.queryForObject(
            """
            SELECT pg_has_role(
                current_user, 'vms_migration_owner', 'MEMBER')
            """, Boolean.class)).thenReturn(false);
        when(jdbc.queryForObject(
            "SELECT pg_has_role(current_user, ?, 'MEMBER')",
            Boolean.class, "vms_job_worker")).thenReturn(true);
        when(jdbc.queryForObject(
            """
            SELECT count(*)
            FROM pg_roles capability
            WHERE capability.rolname IN (
                'vms_app_runtime', 'vms_reporting', 'vms_job_worker',
                'vms_migration_processor', 'vms_backup')
              AND capability.rolname <> ?
              AND pg_has_role(
                    current_user, capability.oid, 'MEMBER')
            """, Integer.class, "vms_job_worker")).thenReturn(0);
        when(jdbc.queryForObject(
            """
            SELECT rolcanlogin
                   AND NOT rolsuper
                   AND NOT rolcreatedb
                   AND NOT rolcreaterole
                   AND rolinherit
                   AND NOT rolreplication
                   AND NOT rolbypassrls
            FROM pg_roles
            WHERE rolname = current_user
            """, Boolean.class)).thenReturn(false);

        assertThrows(IllegalStateException.class, () ->
            new DatabaseRoleGuard(
                jdbc, true, "vms_worker_login", "vms_job_worker").run(null));
    }
}
