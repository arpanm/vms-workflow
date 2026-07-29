package com.vms.workflow.security;

import com.vms.workflow.domain.Membership;
import com.vms.workflow.infrastructure.AuthorizationStore;
import com.vms.workflow.infrastructure.MembershipRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class TenantAuthorizationService {
    public static final String CATALOG_READ = "catalog.read";

    private final MembershipRepository memberships;
    private final AuthorizationStore authorization;
    private final Clock clock;

    public TenantAuthorizationService(
        MembershipRepository memberships,
        AuthorizationStore authorization,
        Clock clock
    ) {
        this.memberships = memberships;
        this.authorization = authorization;
        this.clock = clock;
    }

    public List<Membership> sessionMemberships(String subject) {
        return authorizedMemberships(subject, true);
    }

    public List<Membership> organizationMemberships(String subject) {
        return authorizedMemberships(subject, false);
    }

    private List<Membership> authorizedMemberships(String subject, boolean anyValidScope) {
        requireActivePrincipal(subject);
        LocalDate today = LocalDate.now(clock);
        List<Membership> authorized = memberships.findActiveForSubject(subject, today).stream()
            .filter(membership -> anyValidScope
                ? authorization.hasAnyScopedPermission(
                    subject, membership.getOrganization().getId(), CATALOG_READ, today)
                : authorization.hasOrganizationPermission(
                    subject, membership.getOrganization().getId(), CATALOG_READ, today))
            .toList();
        if (authorized.isEmpty()) {
            throw new AccessDeniedException("The authenticated identity has no active catalog.read assignment.");
        }
        return authorized;
    }

    public void requireActivePrincipal(String subject) {
        if (!authorization.hasActivePrincipal(subject, LocalDate.now(clock))) {
            throw new AccessDeniedException("The authenticated identity has no active application access.");
        }
    }

    public void requireOrganization(String subject, UUID organizationId) {
        requireOrganizationPermission(subject, organizationId, CATALOG_READ);
    }

    public void requireOrganizationPermission(
        String subject,
        UUID organizationId,
        String permission
    ) {
        requireActivePrincipal(subject);
        if (!authorization.hasOrganizationPermission(
            subject, organizationId, permission, LocalDate.now(clock))) {
            throw notFound();
        }
    }

    public void requireEngagement(String subject, UUID engagementId) {
        requireEngagementPermission(subject, engagementId, CATALOG_READ);
    }

    public void requireEngagementPermission(
        String subject,
        UUID engagementId,
        String permission
    ) {
        requireActivePrincipal(subject);
        if (!authorization.hasEngagementPermission(
            subject, engagementId, permission, LocalDate.now(clock))) {
            throw notFound();
        }
    }

    public List<String> effectivePermissions(String subject) {
        requireActivePrincipal(subject);
        return authorization.findEffectivePermissions(
            subject,
            LocalDate.now(clock));
    }

    public void requireProject(String subject, UUID projectId) {
        requireProjectPermission(subject, projectId, CATALOG_READ);
    }

    public void requireProjectPermission(
        String subject,
        UUID projectId,
        String permission
    ) {
        requireActivePrincipal(subject);
        if (!authorization.hasProjectPermission(
            subject, projectId, permission, LocalDate.now(clock))) {
            throw notFound();
        }
    }

    public ProjectListScope projectListScope(String subject, UUID engagementId) {
        requireActivePrincipal(subject);
        LocalDate today = LocalDate.now(clock);
        if (authorization.hasEngagementPermission(subject, engagementId, CATALOG_READ, today)) {
            return new ProjectListScope(true, List.of());
        }
        List<UUID> projectIds = authorization.findProjectScopedIds(
            subject, engagementId, CATALOG_READ, today);
        if (projectIds.isEmpty()) {
            throw notFound();
        }
        return new ProjectListScope(false, projectIds);
    }

    public record ProjectListScope(boolean allProjects, List<UUID> projectIds) {
    }

    private EntityNotFoundException notFound() {
        return new EntityNotFoundException("Resource not found.");
    }
}
