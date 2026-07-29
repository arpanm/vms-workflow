package com.vms.workflow.security;

import com.vms.workflow.infrastructure.AuthorizationStore;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class DeliveryAuthorizationService {
    public static final String PLAN_READ = "delivery.plan.read";
    public static final String PLAN_MANAGE = "delivery.plan.manage";
    public static final String PLAN_SUBMIT = "delivery.plan.submit";
    public static final String PLAN_APPROVE = "delivery.plan.approve";
    public static final String LINEAR_READ = "linear.integration.read";
    public static final String LINEAR_MANAGE = "linear.integration.manage";
    public static final String LINEAR_REPLAY = "linear.integration.replay";
    public static final String COMMITMENT_REPLAY = "delivery.commitment.replay";

    private final AuthorizationStore authorization;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public DeliveryAuthorizationService(
        AuthorizationStore authorization,
        JdbcTemplate jdbc,
        Clock clock
    ) {
        this.authorization = authorization;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void requireMonth(String subject, UUID engagementMonthId, String permission) {
        UUID engagementId = jdbc.query("""
            SELECT engagement_id FROM engagement_months WHERE id = ?
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, engagementMonthId);
        requireEngagement(subject, engagementId, permission);
    }

    public void requirePlan(String subject, UUID planId, String permission) {
        UUID engagementId = jdbc.query("""
            SELECT month.engagement_id
            FROM delivery_plans plan
            JOIN engagement_months month ON month.id = plan.engagement_month_id
            WHERE plan.id = ?
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, planId);
        requireEngagement(subject, engagementId, permission);
    }

    /**
     * Resolves either the actor's direct plan authority or one active,
     * in-scope delegation from a configured authority holder. The caller must
     * still verify that the returned holder is an approver on the exact plan
     * version.
     */
    public PlanApprovalAuthority resolvePlanApprovalAuthority(
        String subject,
        UUID planId,
        String onBehalfOfSubject
    ) {
        UUID engagementId = jdbc.query("""
            SELECT month.engagement_id
            FROM delivery_plans plan
            JOIN engagement_months month ON month.id = plan.engagement_month_id
            WHERE plan.id = ?
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, planId);
        LocalDate today = LocalDate.now(clock);
        if (engagementId == null
            || !authorization.hasActivePrincipal(subject, today)) {
            throw new EntityNotFoundException("Resource not found.");
        }
        boolean direct = authorization.hasEngagementPermission(
            subject, engagementId, PLAN_APPROVE, today);
        if (direct && (onBehalfOfSubject == null
            || onBehalfOfSubject.isBlank()
            || subject.equals(onBehalfOfSubject))) {
            return new PlanApprovalAuthority(subject, subject, null, null);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        PlanApprovalAuthority delegated = jdbc.query("""
            SELECT delegator.identity_subject, delegation.id,
                   delegation.valid_to
            FROM delivery_plans plan
            JOIN delivery_plan_versions version
              ON version.id = plan.current_version_id
            JOIN engagement_months month
              ON month.id = plan.engagement_month_id
            JOIN delegations delegation
              ON delegation.engagement_id = month.engagement_id
             AND delegation.status = 'ACTIVE'
             AND ? >= delegation.valid_from
             AND ? < delegation.valid_to
             AND ? = ANY(delegation.action_codes)
            JOIN user_profiles delegate
              ON delegate.id = delegation.delegate_user_id
             AND delegate.identity_subject = ?
             AND delegate.status = 'ACTIVE'
            JOIN user_profiles delegator
              ON delegator.id = delegation.delegator_user_id
             AND delegator.status = 'ACTIVE'
            JOIN memberships membership
              ON membership.user_profile_id = delegate.id
             AND membership.organization_id = delegation.organization_id
             AND membership.status = 'ACTIVE'
             AND CURRENT_DATE BETWEEN membership.valid_from
                 AND COALESCE(membership.valid_to, 'infinity'::date)
            WHERE plan.id = ?
              AND (? IS NULL OR ? = '' OR delegator.identity_subject = ?)
              AND (
                  delegation.project_id IS NULL
                  OR NOT EXISTS (
                      SELECT 1
                      FROM delivery_deliverable_versions deliverable
                      WHERE deliverable.plan_version_id = version.id
                        AND deliverable.project_id <> delegation.project_id
                  )
              )
            ORDER BY delegation.valid_to, delegation.id
            LIMIT 1
            """, rs -> rs.next() ? new PlanApprovalAuthority(
                rs.getString(1), subject, rs.getObject(2, UUID.class),
                rs.getObject(3, OffsetDateTime.class)) : null,
            now, now, PLAN_APPROVE, subject, planId,
            onBehalfOfSubject, onBehalfOfSubject, onBehalfOfSubject);
        if (delegated == null) {
            throw new EntityNotFoundException("Resource not found.");
        }
        return delegated;
    }

    public void requireDeliverableVersion(String subject, UUID deliverableVersionId,
                                          String permission) {
        UUID engagementId = jdbc.query("""
            SELECT month.engagement_id
            FROM delivery_deliverable_versions deliverable
            JOIN delivery_plan_versions version ON version.id = deliverable.plan_version_id
            JOIN delivery_plans plan ON plan.id = version.plan_id
            JOIN engagement_months month ON month.id = plan.engagement_month_id
            WHERE deliverable.id = ?
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
            deliverableVersionId);
        requireEngagement(subject, engagementId, permission);
    }

    public void requireIssueLink(String subject, UUID linkId, String permission) {
        UUID engagementId = jdbc.query("""
            SELECT connection.engagement_id
            FROM linear_issue_links link
            JOIN linear_connections connection ON connection.id = link.connection_id
            WHERE link.id = ?
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, linkId);
        requireEngagement(subject, engagementId, permission);
    }

    public void requireConnection(String subject, UUID connectionId, String permission) {
        UUID engagementId = jdbc.query("""
            SELECT engagement_id FROM linear_connections WHERE id = ?
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, connectionId);
        requireEngagement(subject, engagementId, permission);
    }

    public void requireCommitmentOutbox(String subject, UUID outboxId, String permission) {
        UUID engagementId = jdbc.query("""
            SELECT month.engagement_id
            FROM commitment_outbox outbox
            JOIN delivery_plan_versions version ON version.id = outbox.plan_version_id
            JOIN delivery_plans plan ON plan.id = version.plan_id
            JOIN engagement_months month ON month.id = plan.engagement_month_id
            WHERE outbox.id = ?
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, outboxId);
        requireEngagement(subject, engagementId, permission);
    }

    public void requireEngagement(String subject, UUID engagementId, String permission) {
        LocalDate today = LocalDate.now(clock);
        if (engagementId == null
            || !authorization.hasActivePrincipal(subject, today)
            || !authorization.hasEngagementPermission(
                subject, engagementId, permission, today)) {
            throw new EntityNotFoundException("Resource not found.");
        }
    }

    public record PlanApprovalAuthority(
        String approverSubject,
        String actingSubject,
        UUID delegationId,
        OffsetDateTime delegationValidTo
    ) {
        public boolean delegated() {
            return delegationId != null;
        }
    }
}
