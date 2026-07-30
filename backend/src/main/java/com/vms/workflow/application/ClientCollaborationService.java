package com.vms.workflow.application;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.vms.workflow.api.CollaborationDtos.ApprovalInput;
import com.vms.workflow.api.CollaborationDtos.ApprovalView;
import com.vms.workflow.api.CollaborationDtos.AssignmentInput;
import com.vms.workflow.api.CollaborationDtos.ClientUserInput;
import com.vms.workflow.api.CollaborationDtos.ClientUserView;
import com.vms.workflow.api.CollaborationDtos.ClientView;
import com.vms.workflow.api.CollaborationDtos.CommentInput;
import com.vms.workflow.api.CollaborationDtos.CommentView;
import com.vms.workflow.api.CollaborationDtos.CreateWorkItemInput;
import com.vms.workflow.api.CollaborationDtos.DeliveryStatusInput;
import com.vms.workflow.api.CollaborationDtos.EffortInput;
import com.vms.workflow.api.CollaborationDtos.EffortView;
import com.vms.workflow.api.CollaborationDtos.EstimateInput;
import com.vms.workflow.api.CollaborationDtos.EstimateView;
import com.vms.workflow.api.CollaborationDtos.OnboardClientInput;
import com.vms.workflow.api.CollaborationDtos.RoleGrantInput;
import com.vms.workflow.api.CollaborationDtos.UpdateWorkItemInput;
import com.vms.workflow.api.CollaborationDtos.WorkItemAssignmentInput;
import com.vms.workflow.api.CollaborationDtos.WorkItemAssignmentView;
import com.vms.workflow.api.CollaborationDtos.WorkItemLinkInput;
import com.vms.workflow.api.CollaborationDtos.WorkItemLinkView;
import com.vms.workflow.api.CollaborationDtos.WorkItemView;
import com.vms.workflow.api.DomainConflictException;
import com.vms.workflow.infrastructure.AuthorizationStore;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ClientCollaborationService {
    private final JdbcTemplate jdbc;
    private final AuthorizationStore authorization;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public ClientCollaborationService(
        JdbcTemplate jdbc,
        AuthorizationStore authorization,
        Clock clock,
        ObjectMapper objectMapper
    ) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ClientView onboardClient(String subject, OnboardClientInput input) {
        requireOrganizationPermission(
            subject, input.vendorOrganizationId(), "client.onboard");
        UUID organizationId = UUID.randomUUID();
        UUID engagementId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO organizations(
                id, code, legal_name, display_name, organization_type,
                primary_domain, status, default_timezone)
            VALUES (?, ?, ?, ?, 'CLIENT', ?, 'ACTIVE', ?)
            """, organizationId, input.clientCode(), input.legalName(),
            input.displayName(), blankToNull(input.primaryDomain()),
            input.timezone());
        jdbc.update("""
            INSERT INTO engagements(
                id, engagement_code, name, client_organization_id,
                vendor_organization_id, procurement_organization_id,
                finance_organization_id, engagement_model, start_date, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
            """, engagementId, input.engagementCode(), input.engagementName(),
            organizationId, input.vendorOrganizationId(),
            input.procurementOrganizationId(),
            input.procurementOrganizationId(), input.engagementModel(),
            Date.valueOf(input.startDate()));
        jdbc.update("""
            INSERT INTO projects(
                id, engagement_id, project_code, name, description,
                start_date, status)
            VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')
            """, projectId, engagementId, input.projectCode(),
            input.projectName(), "Initial client delivery project",
            Date.valueOf(input.startDate()));
        jdbc.update("UPDATE engagements SET default_project_id = ? WHERE id = ?",
            projectId, engagementId);

        YearMonth first = YearMonth.from(input.startDate());
        for (int offset = 0; offset < 13; offset++) {
            jdbc.update("""
                INSERT INTO engagement_months(
                    id, engagement_id, month_start_date, state, risk_status)
                VALUES (?, ?, ?, 'PLANNING', 'ON_TRACK')
                ON CONFLICT (engagement_id, month_start_date) DO NOTHING
                """, UUID.randomUUID(), engagementId,
                Date.valueOf(first.plusMonths(offset).atDay(1)));
        }
        audit(null, engagementId, "CLIENT_ONBOARDED", subject,
            Map.of("organizationId", organizationId, "projectId", projectId));
        return new ClientView(
            organizationId, input.clientCode(), input.legalName(),
            input.displayName(), "ACTIVE", engagementId,
            input.engagementCode(), projectId, input.projectCode(), 13);
    }

    @Transactional
    public ClientUserView addClientUser(
        String subject,
        UUID clientOrganizationId,
        ClientUserInput input
    ) {
        UUID vendorOrganizationId = vendorOrganizationForClient(clientOrganizationId);
        requireOrganizationPermission(
            subject, vendorOrganizationId, "client.user.manage");
        if (input.validTo() != null && input.validTo().isBefore(input.validFrom())) {
            throw new IllegalArgumentException("validTo must not precede validFrom.");
        }
        UUID userId = jdbc.query("""
                SELECT id FROM user_profiles WHERE identity_subject = ?
                """, rs -> rs.next() ? UUID.fromString(rs.getString(1)) : null,
            input.identitySubject());
        if (userId == null) {
            userId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO user_profiles(
                    id, identity_subject, email, display_name, status)
                VALUES (?, ?, ?, ?, 'ACTIVE')
                """, userId, input.identitySubject(), input.email(),
                input.displayName());
        } else {
            jdbc.update("""
                UPDATE user_profiles
                SET email = ?, display_name = ?, status = 'ACTIVE',
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, input.email(), input.displayName(), userId);
        }
        for (String roleCode : input.roleCodes()) {
            jdbc.update("""
                INSERT INTO memberships(
                    id, user_profile_id, organization_id, role_code, status,
                    valid_from, valid_to)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)
                ON CONFLICT (user_profile_id, organization_id, role_code)
                DO UPDATE SET status = 'ACTIVE', valid_from = EXCLUDED.valid_from,
                              valid_to = EXCLUDED.valid_to
                """, UUID.randomUUID(), userId, clientOrganizationId, roleCode,
                Date.valueOf(input.validFrom()),
                dateOrNull(input.validTo()));
            insertRoleAssignment(
                userId, clientOrganizationId, roleCode, "ORGANIZATION",
                clientOrganizationId, input.validFrom(), input.validTo());
        }
        return clientUser(clientOrganizationId, userId);
    }

    @Transactional
    public ClientUserView grantRole(
        String subject,
        UUID clientOrganizationId,
        UUID userId,
        RoleGrantInput input
    ) {
        UUID vendorOrganizationId = vendorOrganizationForClient(clientOrganizationId);
        requireOrganizationPermission(
            subject, vendorOrganizationId, "client.user.manage");
        ensureMembership(userId, clientOrganizationId);
        validateGrantScope(clientOrganizationId, input.scopeType(), input.scopeId());
        insertRoleAssignment(
            userId, clientOrganizationId, input.roleCode(),
            input.scopeType(), input.scopeId(), input.validFrom(), input.validTo());
        jdbc.update("""
            INSERT INTO memberships(
                id, user_profile_id, organization_id, role_code, status,
                valid_from, valid_to)
            VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)
            ON CONFLICT (user_profile_id, organization_id, role_code)
            DO UPDATE SET status = 'ACTIVE', valid_from = EXCLUDED.valid_from,
                          valid_to = EXCLUDED.valid_to
            """, UUID.randomUUID(), userId, clientOrganizationId,
            input.roleCode(), Date.valueOf(input.validFrom()),
            dateOrNull(input.validTo()));
        return clientUser(clientOrganizationId, userId);
    }

    public List<ClientUserView> clientUsers(
        String subject,
        UUID clientOrganizationId
    ) {
        UUID vendorOrganizationId = vendorOrganizationForClient(clientOrganizationId);
        requireOrganizationPermission(
            subject, vendorOrganizationId, "client.user.manage");
        List<UUID> ids = jdbc.queryForList("""
            SELECT DISTINCT user_profile_id
            FROM memberships
            WHERE organization_id = ? AND status = 'ACTIVE'
            ORDER BY user_profile_id
            """, UUID.class, clientOrganizationId);
        return ids.stream().map(id -> clientUser(clientOrganizationId, id)).toList();
    }

    @Transactional
    public WorkItemView createWorkItem(
        String subject,
        CreateWorkItemInput input
    ) {
        requireEngagementPermission(subject, input.engagementId(), "workitem.create");
        UUID workItemId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO work_items(
                id, engagement_id, project_id, engagement_month_id,
                work_item_code, title, description, workflow_description,
                acceptance_criteria, priority, lifecycle_status,
                created_on_behalf_of_client, created_by_subject,
                updated_by_subject)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, workItemId, input.engagementId(), input.projectId(),
            input.engagementMonthId(), input.workItemCode(), input.title(),
            input.description(), input.workflowDescription(),
            input.acceptanceCriteria(), input.priority(),
            input.lifecycleStatus(), input.createdOnBehalfOfClient(),
            subject, subject);
        for (WorkItemLinkInput link : input.links()) {
            insertLink(subject, workItemId, link);
        }
        for (WorkItemAssignmentInput assignment : input.assignments()) {
            ensureParticipant(input.engagementId(), assignment.userProfileId());
            insertAssignment(
                subject, workItemId, assignment.userProfileId(),
                assignment.discipline());
        }
        audit(workItemId, input.engagementId(), "WORK_ITEM_CREATED", subject,
            Map.of("code", input.workItemCode()));
        return workItem(subject, workItemId);
    }

    @Transactional
    public List<WorkItemView> bulkCreate(
        String subject,
        List<CreateWorkItemInput> inputs
    ) {
        if (inputs.isEmpty() || inputs.size() > 500) {
            throw new IllegalArgumentException(
                "Bulk work-item import requires between 1 and 500 rows.");
        }
        UUID engagementId = inputs.getFirst().engagementId();
        requireEngagementPermission(subject, engagementId, "workitem.bulk.import");
        if (inputs.stream().anyMatch(input -> !engagementId.equals(input.engagementId()))) {
            throw new IllegalArgumentException(
                "A bulk request must contain exactly one engagement.");
        }
        return inputs.stream().map(input -> createWorkItem(subject, input)).toList();
    }

    public List<WorkItemView> workItems(
        String subject,
        UUID engagementId,
        String bucket,
        boolean assignedToMe,
        boolean mentionedToMe
    ) {
        requireEngagementPermission(subject, engagementId, "workitem.read");
        StringBuilder sql = new StringBuilder("""
            SELECT item.id
            FROM work_items item
            LEFT JOIN engagement_months month ON month.id = item.engagement_month_id
            WHERE item.engagement_id = ?
            """);
        List<Object> parameters = new ArrayList<>();
        parameters.add(engagementId);
        YearMonth current = YearMonth.now(clock);
        LocalDate currentStart = current.atDay(1);
        if (bucket != null && !bucket.isBlank() && !"ALL".equals(bucket)) {
            switch (bucket) {
                case "BACKLOG" -> sql.append(
                    " AND (item.engagement_month_id IS NULL OR item.lifecycle_status = 'BACKLOG')");
                case "CURRENT" -> {
                    sql.append(" AND month.month_start_date = ?");
                    parameters.add(Date.valueOf(currentStart));
                }
                case "NEXT" -> {
                    sql.append(" AND month.month_start_date > ?");
                    parameters.add(Date.valueOf(currentStart));
                }
                case "PAST" -> {
                    sql.append(" AND month.month_start_date < ?");
                    parameters.add(Date.valueOf(currentStart));
                }
                default -> throw new IllegalArgumentException("Unsupported work-item bucket.");
            }
        }
        if (assignedToMe) {
            sql.append("""
                 AND EXISTS (
                    SELECT 1 FROM work_item_assignments assignment
                    JOIN user_profiles profile
                      ON profile.id = assignment.user_profile_id
                    WHERE assignment.work_item_id = item.id
                      AND assignment.status = 'ACTIVE'
                      AND profile.identity_subject = ?)
                """);
            parameters.add(subject);
        }
        if (mentionedToMe) {
            sql.append("""
                 AND EXISTS (
                    SELECT 1 FROM work_item_comments comment
                    JOIN work_item_mentions mention
                      ON mention.comment_id = comment.id
                    JOIN user_profiles profile
                      ON profile.id = mention.user_profile_id
                    WHERE comment.work_item_id = item.id
                      AND profile.identity_subject = ?)
                """);
            parameters.add(subject);
        }
        sql.append("""
             ORDER BY item.stack_rank NULLS LAST, item.updated_at DESC, item.id
            """);
        List<UUID> ids = jdbc.queryForList(
            sql.toString(), UUID.class, parameters.toArray());
        return ids.stream().map(id -> assembleWorkItem(id)).toList();
    }

    public WorkItemView workItem(String subject, UUID workItemId) {
        UUID engagementId = workItemEngagement(workItemId);
        requireEngagementPermission(subject, engagementId, "workitem.read");
        return assembleWorkItem(workItemId);
    }

    @Transactional
    public WorkItemView updateWorkItem(
        String subject,
        UUID workItemId,
        UpdateWorkItemInput input
    ) {
        UUID engagementId = workItemEngagement(workItemId);
        requireEngagementPermission(subject, engagementId, "workitem.update");
        requireManagerOrCreator(subject, workItemId, engagementId);
        int changed = jdbc.update("""
            UPDATE work_items
            SET title = ?, description = ?, workflow_description = ?,
                acceptance_criteria = ?, priority = ?,
                engagement_month_id = ?, version = version + 1,
                updated_by_subject = ?, updated_at = CURRENT_TIMESTAMP
            WHERE id = ? AND version = ?
            """, input.title(), input.description(), input.workflowDescription(),
            input.acceptanceCriteria(), input.priority(),
            input.engagementMonthId(), subject, workItemId,
            input.expectedVersion());
        requireChanged(changed, workItemId);
        audit(workItemId, engagementId, "WORK_ITEM_UPDATED", subject, Map.of());
        return assembleWorkItem(workItemId);
    }

    @Transactional
    public WorkItemView updateDeliveryStatus(
        String subject,
        UUID workItemId,
        DeliveryStatusInput input
    ) {
        UUID engagementId = workItemEngagement(workItemId);
        requireEngagementPermission(
            subject, engagementId, "workitem.delivery.update");
        requireAssignedOrManager(subject, workItemId, engagementId);
        int changed = jdbc.update("""
            UPDATE work_items
            SET lifecycle_status = ?, delivery_summary = ?,
                version = version + 1, updated_by_subject = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ? AND version = ?
            """, input.lifecycleStatus(), blankToNull(input.deliverySummary()),
            subject, workItemId, input.expectedVersion());
        requireChanged(changed, workItemId);
        audit(workItemId, engagementId, "DELIVERY_STATUS_UPDATED", subject,
            Map.of("status", input.lifecycleStatus()));
        return assembleWorkItem(workItemId);
    }

    @Transactional
    public WorkItemView addLink(
        String subject,
        UUID workItemId,
        WorkItemLinkInput input
    ) {
        UUID engagementId = workItemEngagement(workItemId);
        requireEngagementPermission(subject, engagementId, "workitem.update");
        requireAssignedOrManager(subject, workItemId, engagementId);
        insertLink(subject, workItemId, input);
        audit(workItemId, engagementId, "WORK_ITEM_LINK_ADDED", subject,
            Map.of("type", input.linkType()));
        return assembleWorkItem(workItemId);
    }

    @Transactional
    public WorkItemView assign(
        String subject,
        UUID workItemId,
        AssignmentInput input
    ) {
        UUID engagementId = workItemEngagement(workItemId);
        requireEngagementPermission(subject, engagementId, "workitem.assign");
        UUID actorUserId = userId(subject);
        if (!isManager(subject, engagementId)
            && !isAssigned(subject, workItemId)
            && !actorUserId.equals(input.userProfileId())) {
            throw new AccessDeniedException(
                "Only an assignee may transfer work, or a user may claim it.");
        }
        ensureParticipant(engagementId, input.userProfileId());
        insertAssignment(
            subject, workItemId, input.userProfileId(), input.discipline());
        audit(workItemId, engagementId, "WORK_ITEM_ASSIGNED", subject,
            Map.of("userProfileId", input.userProfileId(),
                "discipline", input.discipline()));
        return assembleWorkItem(workItemId);
    }

    @Transactional
    public WorkItemView addComment(
        String subject,
        UUID workItemId,
        CommentInput input
    ) {
        UUID engagementId = workItemEngagement(workItemId);
        requireEngagementPermission(subject, engagementId, "workitem.comment");
        UUID commentId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO work_item_comments(
                id, work_item_id, body, author_subject)
            VALUES (?, ?, ?, ?)
            """, commentId, workItemId, input.body(), subject);
        for (UUID mentionedUserId : input.mentionedUserIds().stream().distinct().toList()) {
            ensureParticipant(engagementId, mentionedUserId);
            jdbc.update("""
                INSERT INTO work_item_mentions(
                    comment_id, user_profile_id, mentioned_by_subject)
                VALUES (?, ?, ?)
                """, commentId, mentionedUserId, subject);
        }
        audit(workItemId, engagementId, "WORK_ITEM_COMMENTED", subject,
            Map.of("mentionCount", input.mentionedUserIds().size()));
        return assembleWorkItem(workItemId);
    }

    @Transactional
    public WorkItemView addEstimate(
        String subject,
        UUID workItemId,
        EstimateInput input
    ) {
        UUID engagementId = workItemEngagement(workItemId);
        requireEngagementPermission(subject, engagementId, "workitem.estimate");
        requireSelfOrManager(subject, input.userProfileId(), engagementId);
        ensureParticipant(engagementId, input.userProfileId());
        jdbc.update("""
            INSERT INTO work_item_estimates(
                id, work_item_id, user_profile_id, hours, note,
                created_by_subject)
            VALUES (?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), workItemId, input.userProfileId(),
            input.hours(), blankToNull(input.note()), subject);
        audit(workItemId, engagementId, "WORK_ITEM_ESTIMATED", subject,
            Map.of("hours", input.hours()));
        return assembleWorkItem(workItemId);
    }

    @Transactional
    public WorkItemView deleteEstimate(
        String subject,
        UUID workItemId,
        UUID estimateId
    ) {
        UUID engagementId = workItemEngagement(workItemId);
        requireEngagementPermission(subject, engagementId, "workitem.estimate");
        UUID estimateUserId = jdbc.query("""
            SELECT user_profile_id FROM work_item_estimates
            WHERE id = ? AND work_item_id = ? AND deleted_at IS NULL
            """, rs -> rs.next() ? UUID.fromString(rs.getString(1)) : null,
            estimateId, workItemId);
        if (estimateUserId == null) {
            throw new EntityNotFoundException("Estimate not found.");
        }
        requireSelfOrManager(subject, estimateUserId, engagementId);
        jdbc.update("""
            UPDATE work_item_estimates
            SET deleted_at = CURRENT_TIMESTAMP, deleted_by_subject = ?
            WHERE id = ? AND deleted_at IS NULL
            """, subject, estimateId);
        audit(workItemId, engagementId, "WORK_ITEM_ESTIMATE_DELETED", subject,
            Map.of("estimateId", estimateId));
        return assembleWorkItem(workItemId);
    }

    @Transactional
    public WorkItemView addEffort(
        String subject,
        UUID workItemId,
        EffortInput input
    ) {
        UUID engagementId = workItemEngagement(workItemId);
        requireEngagementPermission(subject, engagementId, "workitem.effort");
        requireSelfOrManager(subject, input.userProfileId(), engagementId);
        ensureParticipant(engagementId, input.userProfileId());
        jdbc.update("""
            INSERT INTO work_item_efforts(
                id, work_item_id, user_profile_id, work_date, hours,
                note, created_by_subject)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), workItemId, input.userProfileId(),
            Date.valueOf(input.workDate()), input.hours(),
            blankToNull(input.note()), subject);
        audit(workItemId, engagementId, "WORK_ITEM_EFFORT_RECORDED", subject,
            Map.of("hours", input.hours(), "workDate", input.workDate()));
        return assembleWorkItem(workItemId);
    }

    @Transactional
    public WorkItemView approve(
        String subject,
        UUID workItemId,
        ApprovalInput input
    ) {
        UUID engagementId = workItemEngagement(workItemId);
        String permission = switch (input.stage()) {
            case "PLAN_L1" -> "workitem.plan.approve";
            case "DELIVERY_L1" -> "workitem.delivery.approve.l1";
            case "DELIVERY_L2" -> "workitem.delivery.approve.l2";
            default -> throw new IllegalArgumentException("Unsupported approval stage.");
        };
        requireEngagementPermission(subject, engagementId, permission);
        Long currentVersion = jdbc.queryForObject(
            "SELECT version FROM work_items WHERE id = ?",
            Long.class, workItemId);
        if (currentVersion == null || currentVersion != input.expectedVersion()) {
            throw new DomainConflictException(
                "WORK_ITEM_VERSION_CONFLICT",
                "Work item changed before approval.", currentVersion);
        }
        if ("PLAN_L1".equals(input.stage())
            && (input.stackRank() == null || input.stackRank() <= 0)) {
            throw new IllegalArgumentException(
                "PLAN_L1 approval requires a positive stack rank.");
        }
        jdbc.update("""
            INSERT INTO work_item_approvals(
                id, work_item_id, stage, decision, stack_rank, comment,
                actor_subject, work_item_version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), workItemId, input.stage(), input.decision(),
            input.stackRank(), blankToNull(input.comment()), subject,
            currentVersion);
        if ("PLAN_L1".equals(input.stage())
            && "APPROVED".equals(input.decision())) {
            jdbc.update("""
                UPDATE work_items
                SET stack_rank = ?, lifecycle_status = 'APPROVED',
                    version = version + 1, updated_by_subject = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, input.stackRank(), subject, workItemId);
        }
        audit(workItemId, engagementId, "WORK_ITEM_" + input.stage(), subject,
            Map.of("decision", input.decision()));
        return assembleWorkItem(workItemId);
    }

    private WorkItemView assembleWorkItem(UUID id) {
        WorkItemView base = jdbc.query("""
            SELECT item.id, item.engagement_id, item.project_id,
                   item.engagement_month_id, month.month_start_date,
                   item.work_item_code, item.title, item.description,
                   item.workflow_description, item.acceptance_criteria,
                   item.priority, item.stack_rank, item.lifecycle_status,
                   item.delivery_summary, item.created_on_behalf_of_client,
                   item.version, item.created_by_subject, item.created_at,
                   item.updated_at,
                   COALESCE((
                       SELECT sum(estimate.hours)
                       FROM work_item_estimates estimate
                       WHERE estimate.work_item_id = item.id
                         AND estimate.deleted_at IS NULL), 0) total_estimate,
                   COALESCE((
                       SELECT sum(effort.hours)
                       FROM work_item_efforts effort
                       WHERE effort.work_item_id = item.id), 0) total_effort
            FROM work_items item
            LEFT JOIN engagement_months month
              ON month.id = item.engagement_month_id
            WHERE item.id = ?
            """, rs -> rs.next() ? mapBaseWorkItem(rs) : null, id);
        if (base == null) {
            throw new EntityNotFoundException("Work item not found.");
        }
        return new WorkItemView(
            base.id(), base.engagementId(), base.projectId(),
            base.engagementMonthId(), base.monthStartDate(),
            base.workItemCode(), base.title(), base.description(),
            base.workflowDescription(), base.acceptanceCriteria(),
            base.priority(), base.stackRank(), base.lifecycleStatus(),
            base.deliverySummary(), base.createdOnBehalfOfClient(),
            base.version(), base.createdBySubject(), base.createdAt(),
            base.updatedAt(), base.totalEstimateHours(),
            base.totalEffortHours(), links(id), assignments(id),
            comments(id), estimates(id), efforts(id), approvals(id));
    }

    private WorkItemView mapBaseWorkItem(ResultSet rs) throws SQLException {
        Date month = rs.getDate("month_start_date");
        return new WorkItemView(
            uuid(rs, "id"), uuid(rs, "engagement_id"),
            uuid(rs, "project_id"), nullableUuid(rs, "engagement_month_id"),
            month == null ? null : month.toLocalDate(),
            rs.getString("work_item_code"), rs.getString("title"),
            rs.getString("description"), rs.getString("workflow_description"),
            rs.getString("acceptance_criteria"), rs.getString("priority"),
            (Integer) rs.getObject("stack_rank"),
            rs.getString("lifecycle_status"),
            rs.getString("delivery_summary"),
            rs.getBoolean("created_on_behalf_of_client"),
            rs.getLong("version"), rs.getString("created_by_subject"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class),
            rs.getBigDecimal("total_estimate"),
            rs.getBigDecimal("total_effort"),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private List<WorkItemLinkView> links(UUID id) {
        return jdbc.query("""
            SELECT id, link_type, label, url, created_by_subject, created_at
            FROM work_item_links WHERE work_item_id = ?
            ORDER BY created_at, id
            """, (rs, row) -> new WorkItemLinkView(
                uuid(rs, "id"), rs.getString("link_type"),
                rs.getString("label"), rs.getString("url"),
                rs.getString("created_by_subject"),
                rs.getObject("created_at", OffsetDateTime.class)), id);
    }

    private List<WorkItemAssignmentView> assignments(UUID id) {
        return jdbc.query("""
            SELECT assignment.id, assignment.user_profile_id,
                   profile.display_name, profile.email, assignment.discipline,
                   assignment.status, assignment.assigned_at
            FROM work_item_assignments assignment
            JOIN user_profiles profile ON profile.id = assignment.user_profile_id
            WHERE assignment.work_item_id = ?
            ORDER BY assignment.status, assignment.assigned_at, assignment.id
            """, (rs, row) -> new WorkItemAssignmentView(
                uuid(rs, "id"), uuid(rs, "user_profile_id"),
                rs.getString("display_name"), rs.getString("email"),
                rs.getString("discipline"), rs.getString("status"),
                rs.getObject("assigned_at", OffsetDateTime.class)), id);
    }

    private List<CommentView> comments(UUID id) {
        return jdbc.query("""
            SELECT id, body, author_subject, created_at
            FROM work_item_comments WHERE work_item_id = ?
            ORDER BY created_at, id
            """, (rs, row) -> {
                UUID commentId = uuid(rs, "id");
                List<UUID> mentions = jdbc.queryForList("""
                    SELECT user_profile_id FROM work_item_mentions
                    WHERE comment_id = ? ORDER BY user_profile_id
                    """, UUID.class, commentId);
                return new CommentView(
                    commentId, rs.getString("body"),
                    rs.getString("author_subject"), mentions,
                    rs.getObject("created_at", OffsetDateTime.class));
            }, id);
    }

    private List<EstimateView> estimates(UUID id) {
        return jdbc.query("""
            SELECT estimate.id, estimate.user_profile_id, profile.display_name,
                   estimate.hours, estimate.note, estimate.deleted_at,
                   estimate.created_at
            FROM work_item_estimates estimate
            JOIN user_profiles profile ON profile.id = estimate.user_profile_id
            WHERE estimate.work_item_id = ?
            ORDER BY estimate.created_at, estimate.id
            """, (rs, row) -> new EstimateView(
                uuid(rs, "id"), uuid(rs, "user_profile_id"),
                rs.getString("display_name"), rs.getBigDecimal("hours"),
                rs.getString("note"), rs.getObject("deleted_at") != null,
                rs.getObject("created_at", OffsetDateTime.class)), id);
    }

    private List<EffortView> efforts(UUID id) {
        return jdbc.query("""
            SELECT effort.id, effort.user_profile_id, profile.display_name,
                   effort.work_date, effort.hours, effort.note,
                   effort.created_at
            FROM work_item_efforts effort
            JOIN user_profiles profile ON profile.id = effort.user_profile_id
            WHERE effort.work_item_id = ?
            ORDER BY effort.work_date, effort.created_at, effort.id
            """, (rs, row) -> new EffortView(
                uuid(rs, "id"), uuid(rs, "user_profile_id"),
                rs.getString("display_name"),
                rs.getDate("work_date").toLocalDate(),
                rs.getBigDecimal("hours"), rs.getString("note"),
                rs.getObject("created_at", OffsetDateTime.class)), id);
    }

    private List<ApprovalView> approvals(UUID id) {
        return jdbc.query("""
            SELECT id, stage, decision, stack_rank, comment, actor_subject,
                   work_item_version, decided_at
            FROM work_item_approvals WHERE work_item_id = ?
            ORDER BY decided_at, id
            """, (rs, row) -> new ApprovalView(
                uuid(rs, "id"), rs.getString("stage"),
                rs.getString("decision"), (Integer) rs.getObject("stack_rank"),
                rs.getString("comment"), rs.getString("actor_subject"),
                rs.getLong("work_item_version"),
                rs.getObject("decided_at", OffsetDateTime.class)), id);
    }

    private ClientUserView clientUser(UUID organizationId, UUID userId) {
        ClientUserView base = jdbc.query("""
            SELECT id, identity_subject, email, display_name, status
            FROM user_profiles WHERE id = ?
            """, rs -> rs.next() ? new ClientUserView(
                uuid(rs, "id"), organizationId,
                rs.getString("identity_subject"), rs.getString("email"),
                rs.getString("display_name"), rs.getString("status"),
                List.of(), List.of()) : null, userId);
        if (base == null) {
            throw new EntityNotFoundException("Client user not found.");
        }
        List<String> roles = jdbc.queryForList("""
            SELECT DISTINCT role_code
            FROM memberships
            WHERE user_profile_id = ? AND organization_id = ?
              AND status = 'ACTIVE'
            ORDER BY role_code
            """, String.class, userId, organizationId);
        List<String> permissions = jdbc.queryForList("""
            SELECT DISTINCT permission.code
            FROM role_assignments assignment
            JOIN roles role ON role.id = assignment.role_id
            JOIN role_permissions mapping ON mapping.role_id = role.id
            JOIN permissions permission ON permission.id = mapping.permission_id
            WHERE assignment.user_profile_id = ?
              AND assignment.organization_id = ?
              AND assignment.status = 'ACTIVE'
              AND assignment.valid_from <= CURRENT_DATE
              AND (assignment.valid_to IS NULL
                   OR assignment.valid_to >= CURRENT_DATE)
            ORDER BY permission.code
            """, String.class, userId, organizationId);
        return new ClientUserView(
            base.userProfileId(), organizationId, base.identitySubject(),
            base.email(), base.displayName(), base.status(), roles, permissions);
    }

    private void insertRoleAssignment(
        UUID userId,
        UUID organizationId,
        String roleCode,
        String scopeType,
        UUID scopeId,
        LocalDate validFrom,
        LocalDate validTo
    ) {
        UUID roleId = jdbc.query("""
            SELECT id FROM roles WHERE code = ? AND status = 'ACTIVE'
            """, rs -> rs.next() ? UUID.fromString(rs.getString(1)) : null,
            roleCode);
        if (roleId == null) {
            throw new IllegalArgumentException("Unknown active role code.");
        }
        jdbc.update("""
            INSERT INTO role_assignments(
                id, user_profile_id, organization_id, role_id,
                scope_type, scope_id, status, valid_from, valid_to)
            VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
            ON CONFLICT (
                user_profile_id, organization_id, role_id,
                scope_type, scope_id, valid_from)
            DO UPDATE SET status = 'ACTIVE', valid_to = EXCLUDED.valid_to
            """, UUID.randomUUID(), userId, organizationId, roleId,
            scopeType, scopeId, Date.valueOf(validFrom), dateOrNull(validTo));
    }

    private void validateGrantScope(
        UUID clientOrganizationId,
        String scopeType,
        UUID scopeId
    ) {
        boolean valid = switch (scopeType) {
            case "ORGANIZATION" -> clientOrganizationId.equals(scopeId);
            case "ENGAGEMENT" -> Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM engagements
                    WHERE id = ? AND client_organization_id = ?)
                """, Boolean.class, scopeId, clientOrganizationId));
            case "PROJECT" -> Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM projects project
                    JOIN engagements engagement ON engagement.id = project.engagement_id
                    WHERE project.id = ?
                      AND engagement.client_organization_id = ?)
                """, Boolean.class, scopeId, clientOrganizationId));
            default -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException(
                "Role assignment scope does not belong to the client.");
        }
    }

    private UUID vendorOrganizationForClient(UUID clientOrganizationId) {
        UUID result = jdbc.query("""
            SELECT vendor_organization_id
            FROM engagements
            WHERE client_organization_id = ?
            ORDER BY start_date, id LIMIT 1
            """, rs -> rs.next() ? UUID.fromString(rs.getString(1)) : null,
            clientOrganizationId);
        if (result == null) {
            throw new EntityNotFoundException("Client engagement not found.");
        }
        return result;
    }

    private void ensureMembership(UUID userId, UUID organizationId) {
        Boolean exists = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM memberships
                WHERE user_profile_id = ? AND organization_id = ?
                  AND status = 'ACTIVE')
            """, Boolean.class, userId, organizationId);
        if (!Boolean.TRUE.equals(exists)) {
            throw new EntityNotFoundException("Client user not found.");
        }
    }

    private void ensureParticipant(UUID engagementId, UUID userId) {
        Boolean exists = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1
                FROM user_profiles profile
                JOIN memberships membership
                  ON membership.user_profile_id = profile.id
                JOIN engagements engagement ON engagement.id = ?
                WHERE profile.id = ? AND profile.status = 'ACTIVE'
                  AND membership.organization_id IN (
                      engagement.client_organization_id,
                      engagement.vendor_organization_id,
                      engagement.procurement_organization_id)
                  AND membership.status = 'ACTIVE'
                  AND membership.valid_from <= CURRENT_DATE
                  AND (membership.valid_to IS NULL
                       OR membership.valid_to >= CURRENT_DATE))
            """, Boolean.class, engagementId, userId);
        if (!Boolean.TRUE.equals(exists)) {
            throw new IllegalArgumentException(
                "Referenced user is not an active engagement participant.");
        }
    }

    private void insertLink(
        String subject,
        UUID workItemId,
        WorkItemLinkInput link
    ) {
        jdbc.update("""
            INSERT INTO work_item_links(
                id, work_item_id, link_type, label, url, created_by_subject)
            VALUES (?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), workItemId, link.linkType(),
            link.label(), link.url(), subject);
    }

    private void insertAssignment(
        String subject,
        UUID workItemId,
        UUID userId,
        String discipline
    ) {
        jdbc.update("""
            INSERT INTO work_item_assignments(
                id, work_item_id, user_profile_id, discipline,
                status, assigned_by_subject)
            VALUES (?, ?, ?, ?, 'ACTIVE', ?)
            ON CONFLICT (work_item_id, user_profile_id, discipline, status)
            DO NOTHING
            """, UUID.randomUUID(), workItemId, userId, discipline, subject);
    }

    private UUID workItemEngagement(UUID workItemId) {
        UUID id = jdbc.query("""
            SELECT engagement_id FROM work_items WHERE id = ?
            """, rs -> rs.next() ? UUID.fromString(rs.getString(1)) : null,
            workItemId);
        if (id == null) {
            throw new EntityNotFoundException("Work item not found.");
        }
        return id;
    }

    private UUID userId(String subject) {
        UUID id = jdbc.query("""
            SELECT id FROM user_profiles
            WHERE identity_subject = ? AND status = 'ACTIVE'
            """, rs -> rs.next() ? UUID.fromString(rs.getString(1)) : null,
            subject);
        if (id == null) {
            throw new AccessDeniedException("Active user profile required.");
        }
        return id;
    }

    private boolean isManager(String subject, UUID engagementId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM user_profiles profile
                JOIN role_assignments assignment
                  ON assignment.user_profile_id = profile.id
                JOIN roles role ON role.id = assignment.role_id
                JOIN engagements engagement ON engagement.id = ?
                WHERE profile.identity_subject = ?
                  AND profile.status = 'ACTIVE'
                  AND role.code IN (
                    'ORG_ADMIN', 'ENGAGEMENT_ADMIN', 'VENDOR_MANAGER',
                    'CLIENT_PRODUCT_OWNER', 'CLIENT_APPROVER')
                  AND assignment.status = 'ACTIVE'
                  AND assignment.valid_from <= CURRENT_DATE
                  AND (assignment.valid_to IS NULL
                       OR assignment.valid_to >= CURRENT_DATE)
                  AND (
                    (assignment.scope_type = 'ORGANIZATION'
                     AND assignment.scope_id IN (
                       engagement.client_organization_id,
                       engagement.vendor_organization_id,
                       engagement.procurement_organization_id))
                    OR (assignment.scope_type = 'ENGAGEMENT'
                        AND assignment.scope_id = engagement.id)
                    OR (assignment.scope_type = 'PROJECT'
                        AND EXISTS (
                          SELECT 1 FROM projects project
                          WHERE project.id = assignment.scope_id
                            AND project.engagement_id = engagement.id)))
            )
            """, Boolean.class, engagementId, subject));
    }

    private boolean isAssigned(String subject, UUID workItemId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM work_item_assignments assignment
                JOIN user_profiles profile
                  ON profile.id = assignment.user_profile_id
                WHERE assignment.work_item_id = ?
                  AND assignment.status = 'ACTIVE'
                  AND profile.identity_subject = ?)
            """, Boolean.class, workItemId, subject));
    }

    private void requireManagerOrCreator(
        String subject,
        UUID workItemId,
        UUID engagementId
    ) {
        String creator = jdbc.queryForObject(
            "SELECT created_by_subject FROM work_items WHERE id = ?",
            String.class, workItemId);
        if (!subject.equals(creator) && !isManager(subject, engagementId)) {
            throw new AccessDeniedException(
                "Only the creator or a scoped manager may edit task definition.");
        }
    }

    private void requireAssignedOrManager(
        String subject,
        UUID workItemId,
        UUID engagementId
    ) {
        if (!isAssigned(subject, workItemId) && !isManager(subject, engagementId)) {
            throw new AccessDeniedException(
                "Only an active assignee or scoped manager may perform this action.");
        }
    }

    private void requireSelfOrManager(
        String subject,
        UUID targetUserId,
        UUID engagementId
    ) {
        if (!userId(subject).equals(targetUserId)
            && !isManager(subject, engagementId)) {
            throw new AccessDeniedException(
                "Only the user or a scoped manager may perform this action.");
        }
    }

    private void requireOrganizationPermission(
        String subject,
        UUID organizationId,
        String permission
    ) {
        LocalDate today = LocalDate.now(clock);
        if (!authorization.hasOrganizationPermission(
            subject, organizationId, permission, today)) {
            throw new AccessDeniedException("Missing organization permission.");
        }
    }

    private void requireEngagementPermission(
        String subject,
        UUID engagementId,
        String permission
    ) {
        LocalDate today = LocalDate.now(clock);
        if (!authorization.hasEngagementPermission(
            subject, engagementId, permission, today)) {
            throw new EntityNotFoundException("Resource not found.");
        }
    }

    private void requireChanged(int changed, UUID workItemId) {
        if (changed == 1) {
            return;
        }
        Long currentVersion = jdbc.query("""
            SELECT version FROM work_items WHERE id = ?
            """, rs -> rs.next() ? rs.getLong(1) : null, workItemId);
        if (currentVersion == null) {
            throw new EntityNotFoundException("Work item not found.");
        }
        throw new DomainConflictException(
            "WORK_ITEM_VERSION_CONFLICT",
            "Work item version is stale.", currentVersion);
    }

    private void audit(
        UUID workItemId,
        UUID engagementId,
        String eventType,
        String subject,
        Map<String, ?> details
    ) {
        try {
            jdbc.update("""
                INSERT INTO work_item_audit_events(
                    id, work_item_id, engagement_id, event_type,
                    actor_subject, details)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)
                """, UUID.randomUUID(), workItemId, engagementId,
                eventType, subject, objectMapper.writeValueAsString(
                    new LinkedHashMap<>(details)));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not serialize audit details.", exception);
        }
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return UUID.fromString(rs.getString(column));
    }

    private static UUID nullableUuid(ResultSet rs, String column)
        throws SQLException {
        String value = rs.getString(column);
        return value == null ? null : UUID.fromString(value);
    }

    private static Object dateOrNull(LocalDate value) {
        return value == null ? null : Date.valueOf(value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
