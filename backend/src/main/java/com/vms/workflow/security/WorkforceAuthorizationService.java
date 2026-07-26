package com.vms.workflow.security;

import com.vms.workflow.infrastructure.AuthorizationStore;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class WorkforceAuthorizationService {
    public static final String WORKFORCE_READ = "workforce.read";
    public static final String WORKFORCE_MANAGE = "workforce.manage";
    public static final String ATTENDANCE_SELF = "attendance.self";
    public static final String ATTENDANCE_REVIEW = "attendance.review";
    public static final String ATTENDANCE_CLOSE = "attendance.close";
    public static final String ATTENDANCE_REOPEN = "attendance.reopen";

    private final AuthorizationStore authorization;
    private final JdbcTemplate jdbc;
    private final Clock clock = Clock.systemUTC();

    public WorkforceAuthorizationService(AuthorizationStore authorization, JdbcTemplate jdbc) {
        this.authorization = authorization;
        this.jdbc = jdbc;
    }

    public void requireOrganizationRead(String subject, UUID organizationId) {
        requireOrganizationPermission(subject, organizationId, WORKFORCE_READ);
    }

    public void requireOrganizationManage(String subject, UUID organizationId) {
        requireOrganizationPermission(subject, organizationId, WORKFORCE_MANAGE);
    }

    public void requireEmployeeRead(String subject, UUID employeeId) {
        UUID organizationId = employeeOrganization(employeeId);
        if (organizationId == null) {
            throw notFound();
        }
        if (hasOrganizationPermission(subject, organizationId, WORKFORCE_READ)
            || isAuthorizedSelf(subject, employeeId)) {
            return;
        }
        throw notFound();
    }

    public void requireEmployeeManage(String subject, UUID employeeId) {
        UUID organizationId = employeeOrganization(employeeId);
        if (organizationId == null
            || !hasOrganizationPermission(subject, organizationId, WORKFORCE_MANAGE)) {
            throw notFound();
        }
    }

    public void requireAttendanceAccess(String subject, UUID employeeId) {
        UUID organizationId = employeeOrganization(employeeId);
        if (organizationId == null) {
            throw notFound();
        }
        if (isAuthorizedSelf(subject, employeeId)
            || hasOrganizationPermission(subject, organizationId, ATTENDANCE_REVIEW)) {
            return;
        }
        throw notFound();
    }

    public void requireAttendanceSelf(String subject, UUID employeeId) {
        if (!isAuthorizedSelf(subject, employeeId)) {
            throw notFound();
        }
    }

    public UUID activeSelfEmployee(String subject) {
        LocalDate today = LocalDate.now(clock);
        List<EmployeeLink> candidates = jdbc.query("""
            SELECT emp.id, emp.organization_id
            FROM employees emp
            JOIN user_profiles profile ON profile.id = emp.user_profile_id
            JOIN employee_versions version ON version.employee_id = emp.id
              AND version.valid_from <= ?
              AND (version.valid_to IS NULL OR version.valid_to >= ?)
            WHERE profile.identity_subject = ?
              AND profile.status = 'ACTIVE'
              AND version.employment_status = 'ACTIVE'
              AND version.activation_status = 'ENABLED'
            ORDER BY emp.id
            """, (rs, rowNum) -> new EmployeeLink(
                rs.getObject("id", UUID.class),
                rs.getObject("organization_id", UUID.class)
            ), today, today, subject);
        List<UUID> authorized = candidates.stream()
            .filter(candidate -> hasOrganizationPermission(
                subject, candidate.organizationId(), ATTENDANCE_SELF))
            .map(EmployeeLink::employeeId)
            .toList();
        if (authorized.size() != 1) {
            throw notFound();
        }
        return authorized.getFirst();
    }

    public void requireEngagementClose(String subject, UUID engagementId) {
        requireEngagementPermission(subject, engagementId, ATTENDANCE_CLOSE);
    }

    public void requireEngagementReopen(String subject, UUID engagementId) {
        requireEngagementPermission(subject, engagementId, ATTENDANCE_REOPEN);
    }

    private boolean isAuthorizedSelf(String subject, UUID employeeId) {
        UUID organizationId = employeeOrganization(employeeId);
        if (organizationId == null) {
            return false;
        }
        LocalDate today = LocalDate.now(clock);
        Boolean linked = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1
                FROM employees emp
                JOIN user_profiles u ON u.id = emp.user_profile_id
                JOIN employee_versions version ON version.employee_id = emp.id
                  AND version.valid_from <= ?
                  AND (version.valid_to IS NULL OR version.valid_to >= ?)
                WHERE emp.id = ?
                  AND u.identity_subject = ?
                  AND u.status = 'ACTIVE'
                  AND version.employment_status = 'ACTIVE'
                  AND version.activation_status = 'ENABLED'
            )
            """, Boolean.class, today, today, employeeId, subject);
        return Boolean.TRUE.equals(linked)
            && authorization.hasOrganizationPermission(
                subject, organizationId, ATTENDANCE_SELF, today);
    }

    private void requireOrganizationPermission(String subject, UUID organizationId, String permission) {
        if (!hasOrganizationPermission(subject, organizationId, permission)) {
            throw new AccessDeniedException("The authenticated identity lacks " + permission + ".");
        }
    }

    private boolean hasOrganizationPermission(String subject, UUID organizationId, String permission) {
        LocalDate today = LocalDate.now(clock);
        return authorization.hasActivePrincipal(subject, today)
            && authorization.hasOrganizationPermission(subject, organizationId, permission, today);
    }

    private void requireEngagementPermission(String subject, UUID engagementId, String permission) {
        LocalDate today = LocalDate.now(clock);
        if (!authorization.hasActivePrincipal(subject, today)
            || !authorization.hasEngagementPermission(subject, engagementId, permission, today)) {
            throw notFound();
        }
    }

    public UUID employeeOrganization(UUID employeeId) {
        return jdbc.query("""
            SELECT organization_id FROM employees WHERE id = ?
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, employeeId);
    }

    private EntityNotFoundException notFound() {
        return new EntityNotFoundException("Resource not found.");
    }

    private record EmployeeLink(UUID employeeId, UUID organizationId) {
    }
}
