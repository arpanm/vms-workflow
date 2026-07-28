package com.vms.workflow.security;

import com.vms.workflow.infrastructure.AuthorizationStore;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import java.util.List;

@Service
public final class MigrationAuthorizationService {
    private final JdbcTemplate jdbc;
    private final AuthorizationStore authorization;
    private final Clock clock;

    public MigrationAuthorizationService(
        JdbcTemplate jdbc,
        AuthorizationStore authorization,
        Clock clock
    ) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.clock = clock;
    }

    public Scope requireEngagement(
        String subject,
        UUID engagementId,
        String permission
    ) {
        Scope scope = jdbc.query("""
            SELECT engagement.id,
                   engagement.vendor_organization_id,
                   engagement.client_organization_id,
                   engagement.procurement_organization_id,
                   engagement.finance_organization_id
            FROM engagements engagement
            WHERE engagement.id = ?
            """, result -> result.next()
                ? new Scope(
                    result.getObject(1, UUID.class),
                    result.getObject(2, UUID.class),
                    result.getObject(3, UUID.class),
                    result.getObject(4, UUID.class),
                    result.getObject(5, UUID.class))
                : null, engagementId);
        if (scope == null) {
            throw notFound();
        }
        LocalDate today = LocalDate.now(clock);
        if (subject == null
            || !authorization.hasActivePrincipal(subject, today)
            || !authorization.hasEngagementPermission(
                subject, engagementId, permission, today)) {
            throw new AccessDeniedException(
                "The authenticated identity lacks scoped migration authority.");
        }
        return scope;
    }

    public Scope requireJob(String subject, UUID jobId, String permission) {
        UUID engagementId = jdbc.query("""
            SELECT engagement_id FROM migration_jobs WHERE id = ?
            """, result -> result.next()
                ? result.getObject(1, UUID.class) : null, jobId);
        if (engagementId == null) {
            throw notFound();
        }
        return requireEngagementWithoutDisclosure(
            subject, engagementId, permission);
    }

    public Scope requireReport(
        String subject,
        UUID reportId,
        String permission
    ) {
        UUID engagementId = jdbc.query("""
            SELECT job.engagement_id
            FROM migration_reconciliation_reports report
            JOIN migration_jobs job ON job.id = report.job_id
            WHERE report.id = ?
            """, result -> result.next()
                ? result.getObject(1, UUID.class) : null, reportId);
        if (engagementId == null) {
            throw notFound();
        }
        return requireEngagementWithoutDisclosure(
            subject, engagementId, permission);
    }

    public void requireOrganizationInScope(Scope scope, UUID organizationId) {
        if (organizationId == null || (
            !organizationId.equals(scope.vendorOrganizationId())
                && !organizationId.equals(scope.clientOrganizationId())
                && !organizationId.equals(scope.procurementOrganizationId())
                && !organizationId.equals(scope.financeOrganizationId()))) {
            throw new AccessDeniedException(
                "Organization is outside the authorized engagement scope.");
        }
    }

    public boolean has(
        String subject,
        UUID engagementId,
        String permission
    ) {
        LocalDate today = LocalDate.now(clock);
        return subject != null
            && authorization.hasActivePrincipal(subject, today)
            && authorization.hasEngagementPermission(
                subject, engagementId, permission, today);
    }

    public ApprovalAuthority requireApprovalAuthority(
        String subject,
        UUID engagementId
    ) {
        requireEngagement(subject, engagementId, "migration.approve");
        LocalDate today = LocalDate.now(clock);
        List<AuthorityAssignment> assignments = jdbc.query("""
            SELECT role.code, assignment.organization_id
            FROM user_profiles profile
            JOIN role_assignments assignment
              ON assignment.user_profile_id = profile.id
            JOIN roles role ON role.id = assignment.role_id
            JOIN engagements engagement ON engagement.id = ?
            WHERE profile.identity_subject = ?
              AND profile.status = 'ACTIVE'
              AND assignment.status = 'ACTIVE'
              AND assignment.valid_from <= ?
              AND (assignment.valid_to IS NULL OR assignment.valid_to >= ?)
              AND (
                (assignment.scope_type = 'ENGAGEMENT'
                  AND assignment.scope_id = engagement.id)
                OR (assignment.scope_type = 'ORGANIZATION'
                  AND assignment.scope_id = assignment.organization_id
                  AND assignment.organization_id IN (
                    engagement.vendor_organization_id,
                    engagement.client_organization_id,
                    engagement.procurement_organization_id,
                    engagement.finance_organization_id))
              )
              AND role.code IN (
                'MIGRATION_LEAD', 'GOVERNANCE_REVIEWER',
                'CLIENT_PRODUCT_OWNER', 'PROCUREMENT_REVIEWER')
            ORDER BY CASE role.code
              WHEN 'MIGRATION_LEAD' THEN 1
              WHEN 'GOVERNANCE_REVIEWER' THEN 2
              WHEN 'CLIENT_PRODUCT_OWNER' THEN 3
              ELSE 4 END
            """, (rs, ignored) -> new AuthorityAssignment(
                rs.getString(1), rs.getObject(2, UUID.class)),
            engagementId, subject, today, today);
        if (assignments.isEmpty()) {
            throw new AccessDeniedException(
                "No active migration sign-off authority is assigned.");
        }
        if (assignments.size() != 1) {
            throw new AccessDeniedException(
                "Migration sign-off authority is ambiguous; retain exactly one active scoped assignment.");
        }
        AuthorityAssignment selected = assignments.getFirst();
        String role = "MIGRATION_LEAD".equals(selected.roleCode())
            ? "MIGRATION_LEAD" : "GOVERNANCE";
        return new ApprovalAuthority(
            role, selected.roleCode(), selected.organizationId());
    }

    public List<UUID> authorizedEngagements(
        String subject,
        String permission
    ) {
        LocalDate today = LocalDate.now(clock);
        return jdbc.queryForList("""
            SELECT DISTINCT engagement.id
            FROM user_profiles profile
            JOIN memberships membership
              ON membership.user_profile_id = profile.id
            JOIN role_assignments assignment
              ON assignment.user_profile_id = profile.id
             AND assignment.organization_id = membership.organization_id
            JOIN roles role ON role.id = assignment.role_id
             AND role.status = 'ACTIVE'
            JOIN role_permissions role_permission
              ON role_permission.role_id = role.id
            JOIN permissions granted
              ON granted.id = role_permission.permission_id
             AND granted.code = ?
            JOIN engagements engagement ON (
              (assignment.scope_type = 'ENGAGEMENT'
                AND assignment.scope_id = engagement.id)
              OR (assignment.scope_type = 'ORGANIZATION'
                AND assignment.scope_id = membership.organization_id
                AND membership.organization_id IN (
                  engagement.vendor_organization_id,
                  engagement.client_organization_id,
                  engagement.procurement_organization_id,
                  engagement.finance_organization_id)))
            WHERE profile.identity_subject = ?
              AND profile.status = 'ACTIVE'
              AND membership.status = 'ACTIVE'
              AND membership.valid_from <= ?
              AND (membership.valid_to IS NULL OR membership.valid_to >= ?)
              AND assignment.status = 'ACTIVE'
              AND assignment.valid_from <= ?
              AND (assignment.valid_to IS NULL OR assignment.valid_to >= ?)
            ORDER BY engagement.id
            """, UUID.class, permission, subject,
            today, today, today, today);
    }

    private EntityNotFoundException notFound() {
        return new EntityNotFoundException("Migration resource not found.");
    }

    private Scope requireEngagementWithoutDisclosure(
        String subject,
        UUID engagementId,
        String permission
    ) {
        try {
            return requireEngagement(subject, engagementId, permission);
        } catch (AccessDeniedException exception) {
            throw notFound();
        }
    }

    public record Scope(
        UUID engagementId,
        UUID vendorOrganizationId,
        UUID clientOrganizationId,
        UUID procurementOrganizationId,
        UUID financeOrganizationId
    ) {
    }

    public record ApprovalAuthority(
        String approvalRole,
        String assignmentRole,
        UUID authorityOrganizationId
    ) {
    }

    private record AuthorityAssignment(
        String roleCode,
        UUID organizationId
    ) {
    }
}
