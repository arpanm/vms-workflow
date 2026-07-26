package com.vms.workflow.security;

import com.vms.workflow.infrastructure.AuthorizationStore;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
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

    private final AuthorizationStore authorization;
    private final JdbcTemplate jdbc;
    private final Clock clock = Clock.systemUTC();

    public DeliveryAuthorizationService(AuthorizationStore authorization, JdbcTemplate jdbc) {
        this.authorization = authorization;
        this.jdbc = jdbc;
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

    public void requireEngagement(String subject, UUID engagementId, String permission) {
        LocalDate today = LocalDate.now(clock);
        if (engagementId == null
            || !authorization.hasActivePrincipal(subject, today)
            || !authorization.hasEngagementPermission(
                subject, engagementId, permission, today)) {
            throw new EntityNotFoundException("Resource not found.");
        }
    }
}
