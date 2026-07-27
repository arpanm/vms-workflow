package com.vms.workflow.security;

import com.vms.workflow.infrastructure.AuthorizationStore;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

@Service
public class FinanceAuthorizationService {
    public enum Party {
        ANY, VENDOR, PROCUREMENT, FINANCE, CLIENT
    }

    private final JdbcTemplate jdbc;
    private final AuthorizationStore authorization;
    private final Clock clock;

    public FinanceAuthorizationService(
        JdbcTemplate jdbc,
        AuthorizationStore authorization,
        Clock clock
    ) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.clock = clock;
    }

    public Scope requireMonth(
        String subject,
        UUID monthId,
        String permission,
        Party party
    ) {
        Scope scope = scopeForMonth(monthId);
        require(subject, scope, permission, party);
        return scope;
    }

    public Scope requireInvoice(
        String subject,
        UUID invoiceId,
        String permission,
        Party party
    ) {
        UUID monthId = jdbc.query("""
            SELECT engagement_month_id FROM invoices WHERE id = ?
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, invoiceId);
        if (monthId == null) {
            throw notFound();
        }
        return requireMonth(subject, monthId, permission, party);
    }

    public Scope requireArtifact(
        String subject,
        UUID artifactId,
        String permission
    ) {
        UUID monthId = jdbc.query("""
            SELECT engagement_month_id
            FROM f05_private_artifacts
            WHERE id = ?
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
            artifactId);
        if (monthId == null) {
            throw notFound();
        }
        return requireMonth(
            subject, monthId, permission, Party.ANY);
    }

    public Scope requireExport(
        String subject,
        UUID exportId,
        String permission
    ) {
        UUID engagementId = jdbc.query("""
            SELECT engagement_id FROM f05_report_exports WHERE id = ?
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, exportId);
        if (engagementId == null) {
            throw notFound();
        }
        Scope scope = scopeForEngagement(engagementId);
        require(subject, scope, permission, Party.ANY);
        return scope;
    }

    public Scope requireEngagement(
        String subject,
        UUID engagementId,
        String permission
    ) {
        Scope scope = scopeForEngagement(engagementId);
        require(subject, scope, permission, Party.ANY);
        return scope;
    }

    public Scope requirePackageView(
        String subject,
        UUID packageId,
        UUID monthId
    ) {
        return requirePackageGrant(
            subject, packageId, monthId, "finance.read", "VIEW");
    }

    public Scope requirePackageDownload(
        String subject,
        UUID packageId,
        UUID monthId
    ) {
        return requirePackageGrant(
            subject, packageId, monthId,
            "evidence.package.download", "DOWNLOAD");
    }

    private Scope requirePackageGrant(
        String subject,
        UUID packageId,
        UUID monthId,
        String permission,
        String shareScope
    ) {
        Scope scope = scopeForMonth(monthId);
        LocalDate today = LocalDate.now(clock);
        if (subject != null
            && authorization.hasActivePrincipal(subject, today)
            && authorization.hasEngagementPermission(
                subject, scope.engagementId(), permission, today)) {
            return scope;
        }
        Boolean shared = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1
                FROM evidence_package_shares share
                JOIN user_profiles profile
                  ON profile.identity_subject = share.recipient_subject
                WHERE share.package_version_id = ?
                  AND share.recipient_subject = ?
                  AND profile.status = 'ACTIVE'
                  AND share.revoked_at IS NULL
                  AND share.expires_at > CURRENT_TIMESTAMP
                  AND (
                      share.access_scope = 'DOWNLOAD'
                      OR (? = 'VIEW' AND share.access_scope = 'VIEW')
                  )
            )
            """, Boolean.class, packageId, subject, shareScope);
        if (!Boolean.TRUE.equals(shared)) {
            throw new AccessDeniedException(
                "The authenticated identity lacks scoped package authority.");
        }
        return scope;
    }

    private void require(
        String subject,
        Scope scope,
        String permission,
        Party party
    ) {
        LocalDate today = LocalDate.now(clock);
        if (subject == null
            || !authorization.hasActivePrincipal(subject, today)
            || !authorization.hasEngagementPermission(
                subject, scope.engagementId(), permission, today)
            || !partyMatches(subject, scope, party, today)) {
            throw new AccessDeniedException(
                "The authenticated identity lacks scoped finance authority.");
        }
    }

    private boolean partyMatches(
        String subject,
        Scope scope,
        Party party,
        LocalDate today
    ) {
        if (party == Party.ANY) {
            return true;
        }
        UUID requiredOrganization = switch (party) {
            case VENDOR -> scope.vendorOrganizationId();
            case PROCUREMENT -> scope.procurementOrganizationId();
            case FINANCE -> scope.financeOrganizationId();
            case CLIENT -> scope.clientOrganizationId();
            case ANY -> null;
        };
        return requiredOrganization != null
            && Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM user_profiles user_profile
                    JOIN memberships membership
                      ON membership.user_profile_id = user_profile.id
                    WHERE user_profile.identity_subject = ?
                      AND user_profile.status = 'ACTIVE'
                      AND membership.organization_id = ?
                      AND membership.status = 'ACTIVE'
                      AND membership.valid_from <= ?
                      AND (membership.valid_to IS NULL OR membership.valid_to >= ?)
                )
                """, Boolean.class, subject, requiredOrganization, today, today));
    }

    private Scope scopeForMonth(UUID monthId) {
        Scope value = jdbc.query("""
            SELECT month.id, month.engagement_id,
                   engagement.vendor_organization_id,
                   engagement.client_organization_id,
                   engagement.procurement_organization_id,
                   engagement.finance_organization_id
            FROM engagement_months month
            JOIN engagements engagement ON engagement.id = month.engagement_id
            WHERE month.id = ?
            """, rs -> rs.next() ? new Scope(
                rs.getObject("id", UUID.class),
                rs.getObject("engagement_id", UUID.class),
                rs.getObject("vendor_organization_id", UUID.class),
                rs.getObject("client_organization_id", UUID.class),
                rs.getObject("procurement_organization_id", UUID.class),
                rs.getObject("finance_organization_id", UUID.class))
                : null, monthId);
        if (value == null) {
            throw notFound();
        }
        return value;
    }

    private Scope scopeForEngagement(UUID engagementId) {
        Scope value = jdbc.query("""
            SELECT id, vendor_organization_id, client_organization_id,
                   procurement_organization_id, finance_organization_id
            FROM engagements WHERE id = ?
            """, rs -> rs.next() ? new Scope(
                null,
                rs.getObject("id", UUID.class),
                rs.getObject("vendor_organization_id", UUID.class),
                rs.getObject("client_organization_id", UUID.class),
                rs.getObject("procurement_organization_id", UUID.class),
                rs.getObject("finance_organization_id", UUID.class))
                : null, engagementId);
        if (value == null) {
            throw notFound();
        }
        return value;
    }

    private EntityNotFoundException notFound() {
        return new EntityNotFoundException("Finance resource not found.");
    }

    public record Scope(
        UUID monthId,
        UUID engagementId,
        UUID vendorOrganizationId,
        UUID clientOrganizationId,
        UUID procurementOrganizationId,
        UUID financeOrganizationId
    ) {
    }
}
