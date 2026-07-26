package com.vms.workflow.security;

import com.vms.workflow.api.CertificationDtos.CertificationPermissions;
import com.vms.workflow.infrastructure.AuthorizationStore;
import com.vms.workflow.application.CertificationSecurityEventService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class CertificationAuthorizationService {
    public static final String READ = "certification.read";
    public static final String SUBMISSION_MANAGE = "certification.submission.manage";
    public static final String SUBMISSION_SUBMIT = "certification.submission.submit";
    public static final String ITEM_DECIDE = "certification.item.decide";
    public static final String SUMMARY_CREATE = "certification.summary.create";
    public static final String CONFIRMATION_REQUEST = "certification.confirmation.request";
    public static final String CONFIRMATION_ACT = "certification.confirmation.act";
    public static final String INBOUND_REVIEW = "certification.inbound.review";
    public static final String INBOUND_INGEST = "certification.inbound.ingest";
    public static final String REOPEN_REQUEST = "certification.reopen.request";
    public static final String REOPEN_APPROVE = "certification.reopen.approve";
    public static final String CLOSE = "certification.close";
    public static final String OUTBOX_REPLAY = "certification.outbox.replay";

    private final AuthorizationStore authorization;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final CertificationSecurityEventService securityEvents;

    public CertificationAuthorizationService(
        AuthorizationStore authorization,
        JdbcTemplate jdbc,
        Clock clock,
        CertificationSecurityEventService securityEvents
    ) {
        this.authorization = authorization;
        this.jdbc = jdbc;
        this.clock = clock;
        this.securityEvents = securityEvents;
    }

    public Scope requireMonthRead(String subject, UUID monthId) {
        UUID engagementId = engagementIdForMonth(monthId);
        LocalDate today = LocalDate.now(clock);
        requireActive(subject, today);
        if (authorization.hasEngagementPermission(subject, engagementId, READ, today)) {
            return new Scope(engagementId, true, List.of());
        }
        List<UUID> projects = authorization.findProjectScopedIds(
            subject, engagementId, READ, today);
        if (projects.isEmpty()) {
            denied(monthId, "CERTIFICATION_MONTH_READ_DENIED", subject,
                "ENGAGEMENT_MONTH", monthId, "NO_VISIBLE_PROJECT_SCOPE");
            throw notFound();
        }
        return new Scope(engagementId, false, projects);
    }

    public void requireVendorSubmission(
        String subject,
        UUID monthId,
        String permission
    ) {
        UUID engagementId = engagementIdForMonth(monthId);
        LocalDate today = LocalDate.now(clock);
        requireActive(subject, today);
        if (!authorization.hasEngagementPermission(
                subject, engagementId, permission, today)
            || !hasPartyAuthority(subject, engagementId, "VENDOR", permission, null)) {
            denied(monthId, "VENDOR_CERTIFICATION_AUTHORITY_DENIED", subject,
                "ENGAGEMENT_MONTH", monthId, "VENDOR_PARTY_OR_PERMISSION_MISSING");
            throw notFound();
        }
    }

    public void requireSubmission(
        String subject,
        UUID submissionId,
        String permission,
        Party party
    ) {
        UUID monthId = jdbc.query("""
            SELECT engagement_month_id FROM delivery_submissions WHERE id = ?
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, submissionId);
        requireMonthParty(subject, monthId, permission, party);
    }

    public void requireItemDecision(
        String subject,
        UUID submissionId,
        UUID deliverableVersionId
    ) {
        ItemScope scope = jdbc.query("""
            SELECT submission.engagement_month_id, month.engagement_id,
                   deliverable.project_id, deliverable.product_owner_subject,
                   deliverable.vendor_owner_subject,
                   submission.created_by_subject,
                   policy.separation_of_duties_required
            FROM delivery_submissions submission
            JOIN engagement_months month ON month.id = submission.engagement_month_id
            JOIN certification_policy_versions policy
              ON policy.id = submission.policy_version_id
            JOIN delivery_deliverable_versions deliverable
              ON deliverable.id = ?
             AND deliverable.plan_version_id = submission.plan_version_id
            WHERE submission.id = ?
            """, rs -> rs.next()
                ? new ItemScope(
                    rs.getObject("engagement_month_id", UUID.class),
                    rs.getObject("engagement_id", UUID.class),
                    rs.getObject("project_id", UUID.class),
                    rs.getString("product_owner_subject"),
                    rs.getString("vendor_owner_subject"),
                    rs.getString("created_by_subject"),
                    rs.getBoolean("separation_of_duties_required"))
                : null, deliverableVersionId, submissionId);
        if (scope == null) {
            throw notFound();
        }
        LocalDate today = LocalDate.now(clock);
        requireActive(subject, today);
        boolean scoped = authorization.hasProjectPermission(
            subject, scope.projectId(), ITEM_DECIDE, today);
        boolean designated = subject.equals(scope.productOwnerSubject())
            || isEligibleClientApprover(subject, submissionId);
        boolean vendorAffiliated = subject.equals(scope.vendorOwnerSubject())
            || hasActiveVendorAffiliation(subject, scope.engagementId());
        boolean separated = !scope.separationOfDutiesRequired()
            || (!subject.equals(scope.submissionAuthorSubject())
                && !vendorAffiliated);
        if (!scoped || !designated || !separated
            || !hasPartyAuthority(
                subject, scope.engagementId(), "CLIENT", ITEM_DECIDE, scope.projectId())) {
            denied(scope.monthId(), "CERTIFICATION_DECISION_DENIED", subject,
                "DELIVERY_SUBMISSION", submissionId,
                separated ? "PROJECT_OR_PARTY_AUTHORITY_MISSING"
                    : "SEPARATION_OF_DUTIES_VIOLATION");
            throw notFound();
        }
    }

    public void requireMonthParty(
        String subject,
        UUID monthId,
        String permission,
        Party party
    ) {
        UUID engagementId = engagementIdForMonth(monthId);
        LocalDate today = LocalDate.now(clock);
        requireActive(subject, today);
        if (!authorization.hasEngagementPermission(
                subject, engagementId, permission, today)
            || !hasPartyAuthority(subject, engagementId, party.name(), permission, null)) {
            denied(monthId, "CERTIFICATION_MONTH_MUTATION_DENIED", subject,
                "ENGAGEMENT_MONTH", monthId,
                "PARTY_OR_PERMISSION_MISSING");
            throw notFound();
        }
    }

    public void requireInboundReview(String subject, UUID monthId) {
        UUID engagementId = engagementIdForMonth(monthId);
        LocalDate today = LocalDate.now(clock);
        requireActive(subject, today);
        if (!authorization.hasEngagementPermission(
                subject, engagementId, INBOUND_REVIEW, today)
            || !hasClientOrProcurementAuthority(
                subject, engagementId, INBOUND_REVIEW)) {
            denied(monthId, "INBOUND_REVIEW_DENIED", subject,
                "ENGAGEMENT_MONTH", monthId,
                "RESTRICTED_REVIEW_AUTHORITY_MISSING");
            throw notFound();
        }
    }

    public void requireInboundIngest(String subject, UUID monthId) {
        UUID engagementId = engagementIdForMonth(monthId);
        LocalDate today = LocalDate.now(clock);
        if (subject == null
            || !authorization.hasActivePrincipal(subject, today)
            || !authorization.hasEngagementPermission(
                subject, engagementId, INBOUND_INGEST, today)
            || !Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM user_profiles
                    WHERE identity_subject = ?
                      AND status = 'ACTIVE'
                      AND principal_type = 'SERVICE'
                )
                """, Boolean.class, subject))) {
            denied(monthId, "INBOUND_INGEST_DENIED", subject,
                "ENGAGEMENT_MONTH", monthId,
                "SERVICE_PRINCIPAL_OR_PERMISSION_MISSING");
            throw notFound();
        }
    }

    public void requireClientOrProcurement(
        String subject,
        UUID monthId,
        String permission
    ) {
        UUID engagementId = engagementIdForMonth(monthId);
        LocalDate today = LocalDate.now(clock);
        requireActive(subject, today);
        if (!authorization.hasEngagementPermission(
                subject, engagementId, permission, today)
            || !hasClientOrProcurementAuthority(
                subject, engagementId, permission)) {
            denied(monthId, "CERTIFICATION_GOVERNANCE_DENIED", subject,
                "ENGAGEMENT_MONTH", monthId,
                "CLIENT_OR_PROCUREMENT_AUTHORITY_MISSING");
            throw notFound();
        }
    }

    public Scope requireRequestRead(String subject, UUID requestId) {
        UUID monthId = monthIdForRequest(requestId);
        Scope scope = requireMonthRead(subject, monthId);
        if (!scope.allProjects() && !Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1
                FROM confirmation_request_eligibility eligibility
                WHERE eligibility.request_id = ?
                  AND eligibility.project_id = ANY (?::uuid[])
            )
            """, Boolean.class, requestId,
            scope.projectIds().toArray(UUID[]::new)))) {
            denied(monthId, "CONFIRMATION_REQUEST_READ_DENIED", subject,
                "BUSINESS_CONFIRMATION_REQUEST", requestId,
                "REQUEST_PROJECT_NOT_VISIBLE");
            throw notFound();
        }
        return scope;
    }

    public void requireConfirmationAction(
        String subject,
        UUID requestId,
        UUID requestedProjectId
    ) {
        UUID monthId = monthIdForRequest(requestId);
        UUID engagementId = engagementIdForMonth(monthId);
        LocalDate today = LocalDate.now(clock);
        requireActive(subject, today);
        List<UUID> eligibleProjects = jdbc.query("""
            SELECT project_id
            FROM confirmation_request_eligibility
            WHERE request_id = ?
              AND eligible_confirmer_subject = ?
              AND (?::uuid IS NULL OR project_id = ?::uuid)
            """, (rs, rowNum) -> rs.getObject("project_id", UUID.class),
            requestId, subject, requestedProjectId, requestedProjectId);
        boolean engagementPermission = authorization.hasEngagementPermission(
            subject, engagementId, CONFIRMATION_ACT, today);
        List<UUID> permittedProjects = authorization.findProjectScopedIds(
            subject, engagementId, CONFIRMATION_ACT, today);
        boolean permission = engagementPermission
            || eligibleProjects.stream()
                .filter(java.util.Objects::nonNull)
                .anyMatch(permittedProjects::contains);
        boolean separated = !confirmationSeparationRequired(requestId)
            || (!hasActiveVendorAffiliation(subject, engagementId)
                && !isVendorOwnerForRequest(subject, requestId));
        if (eligibleProjects.isEmpty() || !permission || !separated
            || !hasPartyAuthority(
                subject, engagementId, "CLIENT", CONFIRMATION_ACT,
                engagementPermission ? null : eligibleProjects.stream()
                    .filter(permittedProjects::contains)
                    .findFirst()
                    .orElse(null))) {
            denied(monthId, "CONFIRMATION_ACTION_DENIED", subject,
                "BUSINESS_CONFIRMATION_REQUEST", requestId,
                separated
                    ? "ELIGIBILITY_PROJECT_OR_PARTY_AUTHORITY_MISSING"
                    : "SEPARATION_OF_DUTIES_VIOLATION");
            throw notFound();
        }
    }

    private boolean confirmationSeparationRequired(UUID requestId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT policy.separation_of_duties_required
            FROM business_confirmation_requests request
            JOIN certification_policy_versions policy
              ON policy.id = request.policy_version_id
            WHERE request.id = ?
            """, Boolean.class, requestId));
    }

    private boolean isVendorOwnerForRequest(String subject, UUID requestId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1
                FROM business_confirmation_requests request
                JOIN delivery_deliverable_versions deliverable
                  ON deliverable.plan_version_id = request.plan_version_id
                WHERE request.id = ?
                  AND deliverable.vendor_owner_subject = ?
            )
            """, Boolean.class, requestId, subject));
    }

    private boolean hasActiveVendorAffiliation(
        String subject,
        UUID engagementId
    ) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1
                FROM user_profiles profile
                JOIN memberships membership
                  ON membership.user_profile_id = profile.id
                JOIN engagements engagement
                  ON engagement.id = ?
                 AND engagement.vendor_organization_id =
                     membership.organization_id
                WHERE profile.identity_subject = ?
                  AND profile.status = 'ACTIVE'
                  AND membership.status = 'ACTIVE'
                  AND membership.valid_from <= CURRENT_DATE
                  AND (
                      membership.valid_to IS NULL
                      OR membership.valid_to >= CURRENT_DATE
                  )
            )
            """, Boolean.class, engagementId, subject));
    }

    public CertificationPermissions permissions(String subject, UUID monthId) {
        UUID engagementId = engagementIdForMonth(monthId);
        LocalDate today = LocalDate.now(clock);
        boolean vendorManage = hasEngagement(subject, engagementId, SUBMISSION_MANAGE, today)
            && hasPartyAuthority(
                subject, engagementId, "VENDOR", SUBMISSION_MANAGE, null);
        boolean vendorSubmit = hasEngagement(subject, engagementId, SUBMISSION_SUBMIT, today)
            && hasPartyAuthority(
                subject, engagementId, "VENDOR", SUBMISSION_SUBMIT, null);
        boolean decide = (hasEngagement(subject, engagementId, ITEM_DECIDE, today)
            || !authorization.findProjectScopedIds(
                subject, engagementId, ITEM_DECIDE, today).isEmpty())
            && hasPartyAuthority(subject, engagementId, "CLIENT", ITEM_DECIDE, null);
        boolean summary = hasEngagement(subject, engagementId, SUMMARY_CREATE, today)
            && hasPartyAuthority(subject, engagementId, "CLIENT", SUMMARY_CREATE, null);
        boolean confirmationRequest =
            hasEngagement(subject, engagementId, CONFIRMATION_REQUEST, today)
                && hasClientOrProcurementAuthority(
                    subject, engagementId, CONFIRMATION_REQUEST);
        boolean confirmationAct =
            (hasEngagement(subject, engagementId, CONFIRMATION_ACT, today)
                || !authorization.findProjectScopedIds(
                    subject, engagementId, CONFIRMATION_ACT, today).isEmpty())
                && hasPartyAuthority(
                    subject, engagementId, "CLIENT", CONFIRMATION_ACT, null);
        boolean inbound = hasEngagement(subject, engagementId, INBOUND_REVIEW, today)
            && hasClientOrProcurementAuthority(subject, engagementId, INBOUND_REVIEW);
        boolean reopen = hasEngagement(subject, engagementId, REOPEN_REQUEST, today)
            && hasClientOrProcurementAuthority(subject, engagementId, REOPEN_REQUEST);
        return new CertificationPermissions(
            vendorManage, vendorSubmit, vendorManage, decide, decide,
            summary, confirmationRequest, confirmationAct, inbound, reopen);
    }

    public boolean hasPermission(String subject, UUID engagementId, String permission) {
        LocalDate today = LocalDate.now(clock);
        return authorization.hasActivePrincipal(subject, today)
            && (authorization.hasEngagementPermission(
                subject, engagementId, permission, today)
                || !authorization.findProjectScopedIds(
                    subject, engagementId, permission, today).isEmpty());
    }

    public boolean hasProjectPartyPermission(
        String subject,
        UUID engagementId,
        UUID projectId,
        String permission,
        Party party
    ) {
        LocalDate today = LocalDate.now(clock);
        if (!authorization.hasActivePrincipal(subject, today)
            || !Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM user_profiles
                    WHERE identity_subject = ?
                      AND status = 'ACTIVE'
                      AND principal_type = 'HUMAN'
                )
                """, Boolean.class, subject))) {
            return false;
        }
        boolean scoped = authorization.hasEngagementPermission(
            subject, engagementId, permission, today)
            || (projectId != null && authorization.hasProjectPermission(
                subject, projectId, permission, today));
        return scoped && hasPartyAuthority(
            subject, engagementId, party.name(), permission, projectId);
    }

    public Set<UUID> authorizedProjectIds(
        String subject,
        UUID engagementId,
        String permission,
        Party party
    ) {
        String partyColumn = switch (party) {
            case CLIENT -> "client_organization_id";
            case VENDOR -> "vendor_organization_id";
            case PROCUREMENT -> "procurement_organization_id";
        };
        LocalDate today = LocalDate.now(clock);
        String sql = """
            SELECT DISTINCT project.id
            FROM projects project
            JOIN engagements engagement
              ON engagement.id = project.engagement_id
             AND engagement.id = ?
            JOIN organizations organization
              ON organization.id = engagement.%s
             AND organization.status = 'ACTIVE'
            JOIN user_profiles profile
              ON profile.identity_subject = ?
             AND profile.status = 'ACTIVE'
             AND profile.principal_type = 'HUMAN'
            JOIN memberships membership
              ON membership.user_profile_id = profile.id
             AND membership.organization_id = organization.id
             AND membership.status = 'ACTIVE'
             AND membership.valid_from <= ?
             AND (membership.valid_to IS NULL OR membership.valid_to >= ?)
            JOIN role_assignments assignment
              ON assignment.user_profile_id = profile.id
             AND assignment.organization_id = organization.id
             AND assignment.status = 'ACTIVE'
             AND assignment.valid_from <= ?
             AND (assignment.valid_to IS NULL OR assignment.valid_to >= ?)
            JOIN roles role
              ON role.id = assignment.role_id
             AND role.status = 'ACTIVE'
            JOIN role_permissions role_permission
              ON role_permission.role_id = role.id
            JOIN permissions permission
              ON permission.id = role_permission.permission_id
             AND permission.code = ?
            WHERE (
                (assignment.scope_type = 'ORGANIZATION'
                    AND assignment.scope_id = organization.id)
                OR (assignment.scope_type = 'ENGAGEMENT'
                    AND assignment.scope_id = engagement.id)
                OR (assignment.scope_type = 'PROJECT'
                    AND assignment.scope_id = project.id)
            )
            ORDER BY project.id
            """.formatted(partyColumn);
        return Set.copyOf(jdbc.queryForList(
            sql, UUID.class, engagementId, subject,
            today, today, today, today, permission));
    }

    private boolean hasEngagement(
        String subject,
        UUID engagementId,
        String permission,
        LocalDate today
    ) {
        return authorization.hasActivePrincipal(subject, today)
            && authorization.hasEngagementPermission(
                subject, engagementId, permission, today);
    }

    private boolean hasPartyAuthority(
        String subject,
        UUID engagementId,
        String party,
        String permission,
        UUID projectId
    ) {
        String partyColumn = switch (party) {
            case "CLIENT" -> "client_organization_id";
            case "VENDOR" -> "vendor_organization_id";
            case "PROCUREMENT" -> "procurement_organization_id";
            default -> throw new IllegalArgumentException("Unsupported engagement party.");
        };
        String projectPredicate = projectId == null
            ? ""
            : " AND (assignment.scope_type <> 'PROJECT' OR assignment.scope_id = ?)";
        String sql = """
            SELECT EXISTS (
                SELECT 1
                FROM user_profiles profile
                JOIN memberships membership ON membership.user_profile_id = profile.id
                JOIN engagements engagement ON engagement.id = ?
                JOIN role_assignments assignment
                  ON assignment.user_profile_id = profile.id
                 AND assignment.organization_id = membership.organization_id
                JOIN roles role ON role.id = assignment.role_id AND role.status = 'ACTIVE'
                JOIN role_permissions role_permission ON role_permission.role_id = role.id
                JOIN permissions permission ON permission.id = role_permission.permission_id
                WHERE profile.identity_subject = ?
                  AND profile.status = 'ACTIVE'
                  AND membership.organization_id = engagement.%s
                  AND membership.status = 'ACTIVE'
                  AND membership.valid_from <= CURRENT_DATE
                  AND (membership.valid_to IS NULL OR membership.valid_to >= CURRENT_DATE)
                  AND assignment.status = 'ACTIVE'
                  AND assignment.valid_from <= CURRENT_DATE
                  AND (assignment.valid_to IS NULL OR assignment.valid_to >= CURRENT_DATE)
                  AND permission.code = ?
                  %s
            )
            """.formatted(partyColumn, projectPredicate);
        Object[] arguments = projectId == null
            ? new Object[]{engagementId, subject, permission}
            : new Object[]{engagementId, subject, permission, projectId};
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, arguments));
    }

    private boolean hasClientOrProcurementAuthority(
        String subject,
        UUID engagementId,
        String permission
    ) {
        return hasPartyAuthority(subject, engagementId, "CLIENT", permission, null)
            || hasPartyAuthority(subject, engagementId, "PROCUREMENT", permission, null);
    }

    private boolean isEligibleClientApprover(String subject, UUID submissionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1
                FROM delivery_submissions submission
                JOIN delivery_plan_approvers approver
                  ON approver.plan_version_id = submission.plan_version_id
                WHERE submission.id = ?
                  AND approver.approver_subject = ?
                  AND approver.authority_snapshot @> '{"eligible":true}'::jsonb
            )
            """, Boolean.class, submissionId, subject));
    }

    private UUID engagementIdForMonth(UUID monthId) {
        if (monthId == null) {
            throw notFound();
        }
        UUID engagementId = jdbc.query("""
            SELECT engagement_id FROM engagement_months WHERE id = ?
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, monthId);
        if (engagementId == null) {
            throw notFound();
        }
        return engagementId;
    }

    private UUID monthIdForRequest(UUID requestId) {
        UUID monthId = jdbc.query("""
            SELECT engagement_month_id
            FROM business_confirmation_requests
            WHERE id = ?
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, requestId);
        if (monthId == null) {
            throw notFound();
        }
        return monthId;
    }

    private void requireActive(String subject, LocalDate today) {
        if (subject == null
            || !authorization.hasActivePrincipal(subject, today)
            || !Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM user_profiles
                    WHERE identity_subject = ?
                      AND status = 'ACTIVE'
                      AND principal_type = 'HUMAN'
                )
                """, Boolean.class, subject))) {
            securityEvents.recordBestEffort(
                null, "F04_HUMAN_AUTHORITY_DENIED", subject,
                "IDENTITY", null, "DENIED",
                "INACTIVE_OR_NON_HUMAN_PRINCIPAL", java.util.Map.of());
            throw notFound();
        }
    }

    private void denied(
        UUID monthId,
        String eventType,
        String subject,
        String objectType,
        UUID objectId,
        String reason
    ) {
        securityEvents.recordBestEffort(
            monthId, eventType, subject, objectType, objectId,
            "DENIED", reason, java.util.Map.of());
    }

    private EntityNotFoundException notFound() {
        return new EntityNotFoundException("Resource not found.");
    }

    public enum Party {
        CLIENT,
        VENDOR,
        PROCUREMENT
    }

    public record Scope(UUID engagementId, boolean allProjects, List<UUID> projectIds) {
    }

    private record ItemScope(
        UUID monthId,
        UUID engagementId,
        UUID projectId,
        String productOwnerSubject,
        String vendorOwnerSubject,
        String submissionAuthorSubject,
        boolean separationOfDutiesRequired
    ) {
    }
}
