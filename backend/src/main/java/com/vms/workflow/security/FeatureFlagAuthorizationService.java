package com.vms.workflow.security;

import com.vms.workflow.infrastructure.AuthorizationStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

@Service
public final class FeatureFlagAuthorizationService {
    private static final String READ = "feature.flag.read";
    private static final String MANAGE = "feature.flag.manage";

    private final AuthorizationStore authorization;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public FeatureFlagAuthorizationService(
        AuthorizationStore authorization,
        JdbcTemplate jdbc,
        Clock clock
    ) {
        this.authorization = authorization;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void requireDefinitionManagement(String subject) {
        if (!hasPlatformPermission(subject, MANAGE)) {
            throw denied();
        }
    }

    public void requireVersionManagement(
        String subject,
        String scopeType,
        UUID organizationId,
        UUID engagementId
    ) {
        LocalDate today = LocalDate.now(clock);
        boolean permitted = switch (scopeType) {
            case "SYSTEM" -> hasPlatformPermission(subject, MANAGE);
            case "ORGANIZATION" ->
                organizationId != null
                    && authorization.hasActivePrincipal(subject, today)
                    && authorization.hasOrganizationPermission(
                        subject, organizationId, MANAGE, today);
            case "ENGAGEMENT" ->
                organizationId != null
                    && engagementId != null
                    && authorization.hasActivePrincipal(subject, today)
                    && authorization.hasEngagementOrganizationPermission(
                        subject, organizationId, engagementId, MANAGE, today);
            default -> false;
        };
        if (!permitted) {
            throw denied();
        }
    }

    public void requireEvaluation(
        String subject,
        UUID organizationId,
        UUID engagementId
    ) {
        LocalDate today = LocalDate.now(clock);
        boolean permitted = authorization.hasActivePrincipal(subject, today)
            && (engagementId == null
                ? authorization.hasOrganizationPermission(
                    subject, organizationId, READ, today)
                : authorization.hasEngagementOrganizationPermission(
                    subject, organizationId, engagementId, READ, today));
        if (!permitted) {
            throw denied();
        }
    }

    public boolean hasPlatformPermission(String subject, String permission) {
        LocalDate today = LocalDate.now(clock);
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1
                FROM user_profiles profile
                JOIN f07_platform_role_assignments assignment
                  ON assignment.user_profile_id = profile.id
                JOIN roles role
                  ON role.id = assignment.role_id
                 AND role.status = 'ACTIVE'
                JOIN role_permissions role_permission
                  ON role_permission.role_id = role.id
                JOIN permissions permission
                  ON permission.id = role_permission.permission_id
                WHERE profile.identity_subject = ?
                  AND profile.status = 'ACTIVE'
                  AND assignment.scope_type = 'SYSTEM'
                  AND assignment.status = 'ACTIVE'
                  AND assignment.valid_from <= ?
                  AND (assignment.valid_to IS NULL
                       OR assignment.valid_to >= ?)
                  AND permission.code = ?
            )
            """, Boolean.class, subject, today, today, permission));
    }

    private AccessDeniedException denied() {
        return new AccessDeniedException(
            "Active scoped feature-flag authority is required.");
    }
}
