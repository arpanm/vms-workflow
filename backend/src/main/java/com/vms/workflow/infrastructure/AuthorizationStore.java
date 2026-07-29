package com.vms.workflow.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public class AuthorizationStore {
    private static final String ACTIVE_PRINCIPAL = """
        SELECT EXISTS (
            SELECT 1
            FROM user_profiles u
            JOIN memberships m ON m.user_profile_id = u.id
            JOIN organizations o ON o.id = m.organization_id
            WHERE u.identity_subject = ?
              AND u.status = 'ACTIVE'
              AND m.status = 'ACTIVE'
              AND m.valid_from <= ?
              AND (m.valid_to IS NULL OR m.valid_to >= ?)
              AND o.status = 'ACTIVE'
        )
        """;

    private static final String ORGANIZATION_PERMISSION = """
        SELECT EXISTS (
            SELECT 1
            FROM user_profiles u
            JOIN memberships m ON m.user_profile_id = u.id
            JOIN organizations o ON o.id = m.organization_id
            JOIN role_assignments ra
              ON ra.user_profile_id = u.id AND ra.organization_id = o.id
            JOIN roles r ON r.id = ra.role_id AND r.status = 'ACTIVE'
            JOIN role_permissions rp ON rp.role_id = r.id
            JOIN permissions p ON p.id = rp.permission_id
            WHERE u.identity_subject = ?
              AND u.status = 'ACTIVE'
              AND o.id = ?
              AND o.status = 'ACTIVE'
              AND m.status = 'ACTIVE'
              AND m.valid_from <= ?
              AND (m.valid_to IS NULL OR m.valid_to >= ?)
              AND ra.status = 'ACTIVE'
              AND ra.valid_from <= ?
              AND (ra.valid_to IS NULL OR ra.valid_to >= ?)
              AND ra.scope_type = 'ORGANIZATION'
              AND ra.scope_id = o.id
              AND p.code = ?
        )
        """;

    private static final String EMPLOYEE_ORGANIZATION_PERMISSION = """
        SELECT EXISTS (
            SELECT 1
            FROM employees emp
            JOIN organizations o ON o.id = emp.organization_id
            JOIN memberships m ON m.organization_id = o.id
            JOIN user_profiles u ON u.id = m.user_profile_id
            JOIN role_assignments ra
              ON ra.user_profile_id = u.id AND ra.organization_id = o.id
            JOIN roles r ON r.id = ra.role_id AND r.status = 'ACTIVE'
            JOIN role_permissions rp ON rp.role_id = r.id
            JOIN permissions p ON p.id = rp.permission_id
            WHERE emp.id = ?
              AND u.identity_subject = ?
              AND u.status = 'ACTIVE'
              AND o.status = 'ACTIVE'
              AND m.status = 'ACTIVE'
              AND m.valid_from <= ?
              AND (m.valid_to IS NULL OR m.valid_to >= ?)
              AND ra.status = 'ACTIVE'
              AND ra.valid_from <= ?
              AND (ra.valid_to IS NULL OR ra.valid_to >= ?)
              AND ra.scope_type = 'ORGANIZATION'
              AND ra.scope_id = o.id
              AND p.code = ?
        )
        """;

    private static final String ANY_SCOPED_PERMISSION = """
        SELECT EXISTS (
            SELECT 1
            FROM user_profiles u
            JOIN memberships m ON m.user_profile_id = u.id
            JOIN organizations o ON o.id = m.organization_id
            JOIN role_assignments ra
              ON ra.user_profile_id = u.id AND ra.organization_id = o.id
            JOIN roles r ON r.id = ra.role_id AND r.status = 'ACTIVE'
            JOIN role_permissions rp ON rp.role_id = r.id
            JOIN permissions p ON p.id = rp.permission_id
            WHERE u.identity_subject = ?
              AND u.status = 'ACTIVE'
              AND o.id = ?
              AND o.status = 'ACTIVE'
              AND m.status = 'ACTIVE'
              AND m.valid_from <= ?
              AND (m.valid_to IS NULL OR m.valid_to >= ?)
              AND ra.status = 'ACTIVE'
              AND ra.valid_from <= ?
              AND (ra.valid_to IS NULL OR ra.valid_to >= ?)
              AND (
                  (ra.scope_type = 'ORGANIZATION' AND ra.scope_id = o.id)
                  OR (ra.scope_type = 'ENGAGEMENT' AND EXISTS (
                      SELECT 1
                      FROM engagements e
                      WHERE e.id = ra.scope_id
                        AND (o.id = e.client_organization_id
                          OR o.id = e.vendor_organization_id
                          OR o.id = e.procurement_organization_id)
                  ))
                  OR (ra.scope_type = 'PROJECT' AND EXISTS (
                      SELECT 1
                      FROM projects pr
                      JOIN engagements e ON e.id = pr.engagement_id
                      WHERE pr.id = ra.scope_id
                        AND (o.id = e.client_organization_id
                          OR o.id = e.vendor_organization_id
                          OR o.id = e.procurement_organization_id)
                  ))
              )
              AND p.code = ?
        )
        """;

    private static final String ENGAGEMENT_PERMISSION = """
        SELECT EXISTS (
            SELECT 1
            FROM user_profiles u
            JOIN memberships m ON m.user_profile_id = u.id
            JOIN organizations o ON o.id = m.organization_id
            JOIN engagements e ON e.id = ?
              AND o.id IN (e.client_organization_id, e.vendor_organization_id, e.procurement_organization_id)
            JOIN role_assignments ra
              ON ra.user_profile_id = u.id AND ra.organization_id = o.id
            JOIN roles r ON r.id = ra.role_id AND r.status = 'ACTIVE'
            JOIN role_permissions rp ON rp.role_id = r.id
            JOIN permissions p ON p.id = rp.permission_id
            WHERE u.identity_subject = ?
              AND u.status = 'ACTIVE'
              AND o.status = 'ACTIVE'
              AND m.status = 'ACTIVE'
              AND m.valid_from <= ?
              AND (m.valid_to IS NULL OR m.valid_to >= ?)
              AND ra.status = 'ACTIVE'
              AND ra.valid_from <= ?
              AND (ra.valid_to IS NULL OR ra.valid_to >= ?)
              AND ((ra.scope_type = 'ORGANIZATION' AND ra.scope_id = o.id)
                OR (ra.scope_type = 'ENGAGEMENT' AND ra.scope_id = e.id))
              AND p.code = ?
        )
        """;

    private static final String ENGAGEMENT_ORGANIZATION_PERMISSION = """
        SELECT EXISTS (
            SELECT 1
            FROM user_profiles u
            JOIN memberships m ON m.user_profile_id = u.id
            JOIN organizations o
              ON o.id = m.organization_id AND o.id = ?
            JOIN engagements e ON e.id = ?
              AND o.id IN (
                  e.client_organization_id,
                  e.vendor_organization_id,
                  e.procurement_organization_id)
            JOIN role_assignments ra
              ON ra.user_profile_id = u.id AND ra.organization_id = o.id
            JOIN roles r ON r.id = ra.role_id AND r.status = 'ACTIVE'
            JOIN role_permissions rp ON rp.role_id = r.id
            JOIN permissions p ON p.id = rp.permission_id
            WHERE u.identity_subject = ?
              AND u.status = 'ACTIVE'
              AND o.status = 'ACTIVE'
              AND m.status = 'ACTIVE'
              AND m.valid_from <= ?
              AND (m.valid_to IS NULL OR m.valid_to >= ?)
              AND ra.status = 'ACTIVE'
              AND ra.valid_from <= ?
              AND (ra.valid_to IS NULL OR ra.valid_to >= ?)
              AND (
                  (ra.scope_type = 'ORGANIZATION' AND ra.scope_id = o.id)
                  OR (ra.scope_type = 'ENGAGEMENT' AND ra.scope_id = e.id)
              )
              AND p.code = ?
        )
        """;

    private static final String PROJECT_PERMISSION = """
        SELECT EXISTS (
            SELECT 1
            FROM user_profiles u
            JOIN memberships m ON m.user_profile_id = u.id
            JOIN organizations o ON o.id = m.organization_id
            JOIN projects pr ON pr.id = ?
            JOIN engagements e ON e.id = pr.engagement_id
              AND o.id IN (e.client_organization_id, e.vendor_organization_id, e.procurement_organization_id)
            JOIN role_assignments ra
              ON ra.user_profile_id = u.id AND ra.organization_id = o.id
            JOIN roles r ON r.id = ra.role_id AND r.status = 'ACTIVE'
            JOIN role_permissions rp ON rp.role_id = r.id
            JOIN permissions p ON p.id = rp.permission_id
            WHERE u.identity_subject = ?
              AND u.status = 'ACTIVE'
              AND o.status = 'ACTIVE'
              AND m.status = 'ACTIVE'
              AND m.valid_from <= ?
              AND (m.valid_to IS NULL OR m.valid_to >= ?)
              AND ra.status = 'ACTIVE'
              AND ra.valid_from <= ?
              AND (ra.valid_to IS NULL OR ra.valid_to >= ?)
              AND ((ra.scope_type = 'ORGANIZATION' AND ra.scope_id = o.id)
                OR (ra.scope_type = 'ENGAGEMENT' AND ra.scope_id = e.id)
                OR (ra.scope_type = 'PROJECT' AND ra.scope_id = pr.id))
              AND p.code = ?
        )
        """;

    private static final String PROJECT_SCOPED_IDS = """
        SELECT DISTINCT pr.id
        FROM user_profiles u
        JOIN memberships m ON m.user_profile_id = u.id
        JOIN organizations o ON o.id = m.organization_id
        JOIN role_assignments ra
          ON ra.user_profile_id = u.id AND ra.organization_id = o.id
        JOIN roles r ON r.id = ra.role_id AND r.status = 'ACTIVE'
        JOIN role_permissions rp ON rp.role_id = r.id
        JOIN permissions p ON p.id = rp.permission_id
        JOIN projects pr ON pr.id = ra.scope_id AND pr.engagement_id = ?
        JOIN engagements e ON e.id = pr.engagement_id
          AND (o.id = e.client_organization_id
            OR o.id = e.vendor_organization_id
            OR o.id = e.procurement_organization_id)
        WHERE u.identity_subject = ?
          AND u.status = 'ACTIVE'
          AND o.status = 'ACTIVE'
          AND m.status = 'ACTIVE'
          AND m.valid_from <= ?
          AND (m.valid_to IS NULL OR m.valid_to >= ?)
          AND ra.status = 'ACTIVE'
          AND ra.valid_from <= ?
          AND (ra.valid_to IS NULL OR ra.valid_to >= ?)
          AND ra.scope_type = 'PROJECT'
          AND p.code = ?
        ORDER BY pr.id
        """;

    private final JdbcTemplate jdbc;

    public AuthorizationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean hasActivePrincipal(String subject, LocalDate today) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            ACTIVE_PRINCIPAL, Boolean.class, subject, today, today));
    }

    public boolean hasOrganizationPermission(String subject, UUID organizationId,
                                             String permission, LocalDate today) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            ORGANIZATION_PERMISSION, Boolean.class,
            subject, organizationId, today, today, today, today, permission));
    }

    public boolean hasEmployeeOrganizationPermission(
        String subject,
        UUID employeeId,
        String permission,
        LocalDate today
    ) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            EMPLOYEE_ORGANIZATION_PERMISSION, Boolean.class,
            employeeId, subject, today, today, today, today, permission));
    }

    public boolean hasAnyScopedPermission(String subject, UUID organizationId,
                                          String permission, LocalDate today) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            ANY_SCOPED_PERMISSION, Boolean.class,
            subject, organizationId, today, today, today, today, permission));
    }

    public boolean hasEngagementPermission(String subject, UUID engagementId,
                                           String permission, LocalDate today) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            ENGAGEMENT_PERMISSION, Boolean.class,
            engagementId, subject, today, today, today, today, permission));
    }

    public boolean hasEngagementOrganizationPermission(
        String subject,
        UUID organizationId,
        UUID engagementId,
        String permission,
        LocalDate today
    ) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            ENGAGEMENT_ORGANIZATION_PERMISSION, Boolean.class,
            organizationId, engagementId, subject,
            today, today, today, today, permission));
    }

    public boolean hasProjectPermission(String subject, UUID projectId,
                                        String permission, LocalDate today) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            PROJECT_PERMISSION, Boolean.class,
            projectId, subject, today, today, today, today, permission));
    }

    public List<UUID> findProjectScopedIds(String subject, UUID engagementId,
                                           String permission, LocalDate today) {
        return jdbc.queryForList(
            PROJECT_SCOPED_IDS, UUID.class,
            engagementId, subject, today, today, today, today, permission);
    }
}
