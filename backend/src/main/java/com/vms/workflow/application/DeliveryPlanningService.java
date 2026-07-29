package com.vms.workflow.application;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.vms.workflow.api.DeliveryDtos.ApprovalRequest;
import com.vms.workflow.api.DeliveryDtos.ApprovalView;
import com.vms.workflow.api.DeliveryDtos.AssignmentRequest;
import com.vms.workflow.api.DeliveryDtos.AssignmentView;
import com.vms.workflow.api.DeliveryDtos.CreatePlanRequest;
import com.vms.workflow.api.DeliveryDtos.CriterionRequest;
import com.vms.workflow.api.DeliveryDtos.CriterionView;
import com.vms.workflow.api.DeliveryDtos.DeliverableRequest;
import com.vms.workflow.api.DeliveryDtos.DeliverableView;
import com.vms.workflow.api.DeliveryDtos.DependencyRequest;
import com.vms.workflow.api.DeliveryDtos.DependencyView;
import com.vms.workflow.api.DeliveryDtos.PlanSummaryView;
import com.vms.workflow.api.DeliveryDtos.PlanView;
import com.vms.workflow.api.DeliveryDtos.RecipientView;
import com.vms.workflow.api.DeliveryDtos.RevisionRequest;
import com.vms.workflow.api.DomainConflictException;
import com.vms.workflow.security.DeliveryAuthorizationService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class DeliveryPlanningService {
    private static final Set<String> BASELINE_TYPES = Set.of(
        "ON_TIME", "LATE_APPROVED", "HISTORICAL_RECONSTRUCTED");
    private static final Set<String> QUORUM_MODES = Set.of("ANY_ONE", "ALL", "N_OF_M");
    private static final Set<String> DEPENDENCY_TYPES = Set.of("INTERNAL", "LINEAR", "EXTERNAL");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final DeliveryAuthorizationService authorization;
    private final LinearIntegrationService linear;
    private final Clock clock;

    public DeliveryPlanningService(
        JdbcTemplate jdbc,
        ObjectMapper objectMapper,
        DeliveryAuthorizationService authorization,
        LinearIntegrationService linear,
        Clock clock
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.authorization = authorization;
        this.linear = linear;
        this.clock = clock;
    }

    public List<PlanSummaryView> plans(String subject, UUID engagementMonthId) {
        authorization.requireMonth(
            subject, engagementMonthId, DeliveryAuthorizationService.PLAN_READ);
        return jdbc.query("""
            SELECT plan.id, plan.engagement_month_id, plan.current_version_id,
                   version.version, version.state, version.title, version.baseline_type,
                   version.checksum, version.created_at, version.frozen_at,
                   (SELECT COUNT(*) FROM delivery_deliverable_versions deliverable
                    WHERE deliverable.plan_version_id = version.id) AS deliverable_count,
                   (SELECT COUNT(*) FROM delivery_plan_approvals approval
                    WHERE approval.plan_version_id = version.id
                      AND approval.decision = 'APPROVE') AS approved_count,
                   version.quorum_mode, version.quorum_required,
                   (SELECT COUNT(*) FROM delivery_plan_approvers approver
                    WHERE approver.plan_version_id = version.id) AS approver_count
            FROM delivery_plans plan
            JOIN delivery_plan_versions version ON version.id = plan.current_version_id
            WHERE plan.engagement_month_id = ?
            """, (rs, rowNum) -> new PlanSummaryView(
                rs.getObject("id", UUID.class),
                rs.getObject("engagement_month_id", UUID.class),
                rs.getObject("current_version_id", UUID.class),
                rs.getInt("version"),
                rs.getString("state"),
                rs.getString("title"),
                rs.getString("baseline_type"),
                rs.getString("checksum"),
                rs.getInt("deliverable_count"),
                rs.getInt("approved_count"),
                requiredApprovals(
                    rs.getString("quorum_mode"),
                    rs.getInt("quorum_required"),
                    rs.getInt("approver_count")),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("frozen_at", OffsetDateTime.class)
            ), engagementMonthId);
    }

    public PlanView plan(String subject, UUID planId) {
        authorization.requirePlan(subject, planId, DeliveryAuthorizationService.PLAN_READ);
        return planView(planId);
    }

    @Transactional
    public PlanView create(String subject, CreatePlanRequest request) {
        authorization.requireMonth(
            subject, request.engagementMonthId(), DeliveryAuthorizationService.PLAN_MANAGE);
        validatePlanRequest(request);
        lockMonth(request.engagementMonthId());
        Boolean exists = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM delivery_plans WHERE engagement_month_id = ?
            )
            """, Boolean.class, request.engagementMonthId());
        if (Boolean.TRUE.equals(exists)) {
            throw new DomainConflictException(
                "An engagement month already has a delivery plan.");
        }
        UUID planId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO delivery_plans
                (id, engagement_month_id, created_by_subject)
            VALUES (?, ?, ?)
            """, planId, request.engagementMonthId(), subject);
        jdbc.update("""
            INSERT INTO delivery_plan_versions
                (id, plan_id, version, state, title, summary, business_outcomes,
                 coordinator_subject, baseline_type, quorum_mode, quorum_required,
                 created_by_subject)
            VALUES (?, ?, 1, 'DRAFT', ?, ?, ?, ?, ?, ?, ?, ?)
            """, versionId, planId, request.title(), request.summary(),
            request.businessOutcomes(), request.coordinatorSubject(),
            request.baselineType(), request.quorumMode(), request.quorumRequired(), subject);
        jdbc.update("""
            UPDATE delivery_plans SET current_version_id = ? WHERE id = ?
            """, versionId, planId);
        insertApprovers(versionId, request.approverSubjects());
        insertRecipients(versionId, request.recipients().arrowFoundry(),
            request.recipients().relianceStakeholders(),
            request.recipients().procurementCc());
        for (DeliverableRequest deliverable : request.deliverables()) {
            insertDeliverable(planId, versionId, deliverable);
        }
        ensureNoDependencyCycle(versionId);
        audit(planId, versionId, "PLAN_CREATED", subject,
            "{\"version\":1,\"provider\":\"LOCAL\"}");
        return planView(planId);
    }

    @Transactional
    public PlanView submit(String subject, UUID planId) {
        authorization.requirePlan(subject, planId, DeliveryAuthorizationService.PLAN_SUBMIT);
        CurrentVersion current = currentVersionForUpdate(planId);
        if (!"DRAFT".equals(current.state())) {
            throw new DomainConflictException("Only a draft plan can be submitted.");
        }
        List<String> blockers = completenessBlockers(current.versionId());
        if (!blockers.isEmpty()) {
            throw new DomainConflictException(
                "Plan completeness blockers: " + String.join("; ", blockers));
        }
        resolveApproverAuthority(subject, current.versionId());
        capturePlanSnapshots(current.versionId());
        String checksum = checksum(current.versionId());
        jdbc.update("""
            UPDATE delivery_plan_versions
            SET state = 'PENDING_APPROVAL', checksum = ?, submitted_at = CURRENT_TIMESTAMP,
                optimistic_version = optimistic_version + 1
            WHERE id = ?
            """, checksum, current.versionId());
        audit(planId, current.versionId(), "PLAN_SUBMITTED", subject,
            "{\"checksum\":\"" + checksum + "\"}");
        return planView(planId);
    }

    @Transactional
    public PlanView approve(String subject, UUID planId, ApprovalRequest request) {
        authorization.requirePlan(subject, planId, DeliveryAuthorizationService.PLAN_APPROVE);
        if (!Set.of("APPROVE", "REJECT").contains(request.decision())) {
            throw new IllegalArgumentException("decision must be APPROVE or REJECT.");
        }
        CurrentVersion current = currentVersionForUpdate(planId);
        if (!"PENDING_APPROVAL".equals(current.state())) {
            throw new DomainConflictException("Plan is not pending approval.");
        }
        Boolean eligible = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM delivery_plan_approvers
                WHERE plan_version_id = ? AND approver_subject = ?
                  AND authority_snapshot @> '{"eligible":true}'::jsonb
            )
            """, Boolean.class, current.versionId(), subject);
        if (!Boolean.TRUE.equals(eligible)) {
            throw new EntityNotFoundException("Resource not found.");
        }
        UUID approvalId = UUID.randomUUID();
        String authoritySnapshot = jdbc.queryForObject("""
            SELECT authority_snapshot::text
            FROM delivery_plan_approvers
            WHERE plan_version_id = ? AND approver_subject = ?
            """, String.class, current.versionId(), subject);
        jdbc.update("""
            INSERT INTO delivery_plan_approvals
                (id, plan_version_id, approver_subject, decision, signed_checksum,
                 authority_snapshot, comment)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
            """, approvalId, current.versionId(), subject, request.decision(),
            current.checksum(), authoritySnapshot,
            request.comment());
        if ("REJECT".equals(request.decision())) {
            jdbc.update("""
                UPDATE delivery_plan_versions
                SET state = 'REJECTED', optimistic_version = optimistic_version + 1
                WHERE id = ?
                """, current.versionId());
            audit(planId, current.versionId(), "PLAN_REJECTED", subject, "{}");
            return planView(planId);
        }
        int approvals = jdbc.queryForObject("""
            SELECT COUNT(*) FROM delivery_plan_approvals
            WHERE plan_version_id = ? AND decision = 'APPROVE'
            """, Integer.class, current.versionId());
        int approvers = jdbc.queryForObject("""
            SELECT COUNT(*) FROM delivery_plan_approvers WHERE plan_version_id = ?
            """, Integer.class, current.versionId());
        if (approvals >= requiredApprovals(
            current.quorumMode(), current.quorumRequired(), approvers)) {
            freeze(planId, current, subject);
        }
        return planView(planId);
    }

    @Transactional
    public PlanView revise(String subject, UUID planId, RevisionRequest request) {
        authorization.requirePlan(subject, planId, DeliveryAuthorizationService.PLAN_MANAGE);
        CurrentVersion current = currentVersionForUpdate(planId);
        if (!"FROZEN".equals(current.state())) {
            throw new DomainConflictException("Only a frozen plan can be revised.");
        }
        UUID nextVersionId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO delivery_plan_versions
                (id, plan_id, version, state, title, summary, business_outcomes,
                 coordinator_subject, baseline_type, quorum_mode, quorum_required,
                 prior_version_id, revision_reason, revision_impact, created_by_subject)
            SELECT ?, plan_id, version + 1, 'DRAFT', title, summary, business_outcomes,
                   coordinator_subject, baseline_type, quorum_mode, quorum_required,
                   id, ?, ?, ?
            FROM delivery_plan_versions WHERE id = ?
            """, nextVersionId, request.reason(), request.impact(), subject, current.versionId());
        jdbc.update("""
            INSERT INTO delivery_plan_approvers
                (plan_version_id, approver_subject, authority_snapshot)
            SELECT ?, approver_subject, authority_snapshot
            FROM delivery_plan_approvers WHERE plan_version_id = ?
            """, nextVersionId, current.versionId());
        jdbc.update("""
            INSERT INTO delivery_recipient_snapshots
                (plan_version_id, arrow_foundry, reliance_stakeholders, procurement_cc)
            SELECT ?, arrow_foundry, reliance_stakeholders, procurement_cc
            FROM delivery_recipient_snapshots WHERE plan_version_id = ?
            """, nextVersionId, current.versionId());
        cloneDeliverables(current.versionId(), nextVersionId, subject);
        jdbc.update("""
            UPDATE delivery_plans SET current_version_id = ? WHERE id = ?
            """, nextVersionId, planId);
        audit(planId, nextVersionId, "PLAN_REVISION_CREATED", subject,
            "{\"priorVersionId\":\"" + current.versionId() + "\"}");
        return planView(planId);
    }

    private void freeze(UUID planId, CurrentVersion current, String actor) {
        UUID baselineId = UUID.randomUUID();
        int deliverableCount = jdbc.queryForObject("""
            SELECT COUNT(*) FROM delivery_deliverable_versions
            WHERE plan_version_id = ?
            """, Integer.class, current.versionId());
        UUID originalBaselineId = jdbc.query("""
            SELECT baseline.id
            FROM delivery_plan_baselines baseline
            JOIN delivery_plan_versions version ON version.id = baseline.plan_version_id
            WHERE version.plan_id = ?
            ORDER BY version.version
            LIMIT 1
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, planId);
        jdbc.update("""
            INSERT INTO delivery_plan_baselines
                (id, plan_version_id, checksum, deliverable_count, original_baseline_id)
            VALUES (?, ?, ?, ?, ?)
            """, baselineId, current.versionId(), current.checksum(),
            deliverableCount, originalBaselineId);
        RecipientView recipients = recipients(current.versionId());
        UUID outboxId = UUID.randomUUID();
        String messageType = current.version() == 1 ? "INITIAL" : "REVISION";
        String subject = "Delivery commitment v" + current.version()
            + " [" + current.checksum().substring(0, 12) + "]";
        String plain = commitmentPlainText(current.versionId(), current.checksum());
        String html = "<article><h1>" + escapeHtml(subject)
            + "</h1><pre>" + escapeHtml(plain) + "</pre></article>";
        jdbc.update("""
            INSERT INTO commitment_outbox
                (id, plan_version_id, baseline_id, message_type, idempotency_key,
                 recipient_snapshot, subject_text, plain_text, html_text,
                 archive_reference)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
            """, outboxId, current.versionId(), baselineId, messageType,
            "commitment:" + current.versionId(), json(recipients),
            subject, plain, html, "db://commitment-outbox/" + outboxId);
        audit(planId, current.versionId(), "PLAN_FROZEN", actor,
            "{\"checksum\":\"" + current.checksum()
                + "\",\"businessAcceptanceChanged\":false}");
        jdbc.update("""
            UPDATE delivery_plan_versions
            SET state = 'FROZEN', frozen_at = CURRENT_TIMESTAMP,
                optimistic_version = optimistic_version + 1
            WHERE id = ?
            """, current.versionId());
        jdbc.update("""
            UPDATE engagement_months month
            SET state = CASE
                WHEN month.state = 'ACTIVE' THEN 'ACTIVE'
                ELSE 'PLAN_APPROVED'
            END,
            updated_at = CURRENT_TIMESTAMP
            FROM delivery_plans plan
            WHERE plan.id = ? AND month.id = plan.engagement_month_id
            """, planId);
    }

    private void capturePlanSnapshots(UUID versionId) {
        List<SnapshotSource> links = jdbc.query("""
            SELECT link.id, current.provider_state_id, current.provider_state_name,
                   current.provider_state_type, current.provider_state_category,
                   current.normalized_state, current.fetched_at, current.payload_hash
            FROM delivery_deliverable_versions deliverable
            JOIN linear_issue_links link ON link.deliverable_version_id = deliverable.id
            LEFT JOIN linear_issue_current current
              ON current.connection_id = link.connection_id
             AND current.linear_issue_uuid = link.linear_issue_uuid
            WHERE deliverable.plan_version_id = ?
            """, (rs, rowNum) -> new SnapshotSource(
                rs.getObject("id", UUID.class),
                rs.getString("provider_state_id"),
                rs.getString("provider_state_name"),
                rs.getString("provider_state_type"),
                rs.getString("provider_state_category"),
                rs.getString("normalized_state"),
                rs.getObject("fetched_at", OffsetDateTime.class),
                rs.getString("payload_hash")
            ), versionId);
        for (SnapshotSource source : links) {
            boolean captured = source.normalizedState() != null;
            jdbc.update("""
                INSERT INTO linear_issue_snapshots
                    (id, issue_link_id, plan_version_id, snapshot_type, status,
                     provider_state, normalized_state, fetched_at, payload_hash,
                     confidence, failure_reason)
                VALUES (?, ?, ?, 'PLAN_TIME', ?, ?::jsonb, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), source.linkId(), versionId,
                captured ? "CAPTURED" : "FETCH_FAILED",
                captured ? json(new ProviderState(
                    source.stateId(), source.stateName(), source.stateType(),
                    source.stateCategory())) : "{}",
                source.normalizedState(), source.fetchedAt(), source.payloadHash(),
                captured ? "CURRENT_STATE_ONLY" : "UNAVAILABLE",
                captured ? null : "NO_LOCAL_CURRENT_STATE");
        }
    }

    private void resolveApproverAuthority(String submittingSubject, UUID versionId) {
        Set<String> conflicts = new HashSet<>(jdbc.queryForList("""
            SELECT subject
            FROM (
                SELECT created_by_subject AS subject
                FROM delivery_plan_versions WHERE id = ?
                UNION
                SELECT coordinator_subject
                FROM delivery_plan_versions WHERE id = ?
                UNION
                SELECT product_owner_subject
                FROM delivery_deliverable_versions WHERE plan_version_id = ?
                UNION
                SELECT vendor_owner_subject
                FROM delivery_deliverable_versions WHERE plan_version_id = ?
            ) conflict
            """, String.class, versionId, versionId, versionId, versionId));
        conflicts.add(submittingSubject);
        List<String> configuredApprovers = jdbc.queryForList("""
            SELECT approver_subject
            FROM delivery_plan_approvers
            WHERE plan_version_id = ?
            ORDER BY approver_subject
            """, String.class, versionId);
        for (String approver : configuredApprovers) {
            if (conflicts.contains(approver)) {
                throw new DomainConflictException(
                    "APPROVER_SEPARATION_OF_DUTIES_CONFLICT:" + approver);
            }
            List<AuthorityAssignment> assignments = jdbc.query("""
                SELECT assignment.id, role.code, assignment.organization_id,
                       assignment.scope_type, assignment.scope_id,
                       assignment.valid_from, assignment.valid_to
                FROM delivery_plan_versions version
                JOIN delivery_plans plan ON plan.id = version.plan_id
                JOIN engagement_months month ON month.id = plan.engagement_month_id
                JOIN engagements engagement ON engagement.id = month.engagement_id
                JOIN user_profiles profile
                  ON profile.identity_subject = ? AND profile.status = 'ACTIVE'
                JOIN memberships membership
                  ON membership.user_profile_id = profile.id
                 AND membership.status = 'ACTIVE'
                 AND CURRENT_DATE BETWEEN membership.valid_from
                     AND COALESCE(membership.valid_to, 'infinity'::date)
                JOIN role_assignments assignment
                  ON assignment.user_profile_id = profile.id
                 AND assignment.organization_id = membership.organization_id
                 AND assignment.status = 'ACTIVE'
                 AND CURRENT_DATE BETWEEN assignment.valid_from
                     AND COALESCE(assignment.valid_to, 'infinity'::date)
                JOIN roles role ON role.id = assignment.role_id
                                AND role.status = 'ACTIVE'
                JOIN role_permissions role_permission
                  ON role_permission.role_id = role.id
                JOIN permissions permission
                  ON permission.id = role_permission.permission_id
                 AND permission.code = 'delivery.plan.approve'
                WHERE version.id = ?
                  AND membership.organization_id IN (
                      engagement.client_organization_id,
                      engagement.vendor_organization_id,
                      engagement.procurement_organization_id
                  )
                  AND (
                      (assignment.scope_type = 'ORGANIZATION'
                       AND assignment.scope_id = assignment.organization_id)
                      OR (assignment.scope_type = 'ENGAGEMENT'
                          AND assignment.scope_id = engagement.id)
                      OR (assignment.scope_type = 'PROJECT'
                          AND EXISTS (
                              SELECT 1
                              FROM delivery_deliverable_versions deliverable
                              WHERE deliverable.plan_version_id = version.id
                                AND deliverable.project_id = assignment.scope_id
                          ))
                  )
                ORDER BY assignment.id
                """, (rs, rowNum) -> new AuthorityAssignment(
                    rs.getObject("id", UUID.class),
                    rs.getString("code"),
                    rs.getObject("organization_id", UUID.class),
                    rs.getString("scope_type"),
                    rs.getObject("scope_id", UUID.class),
                    rs.getObject("valid_from", LocalDate.class),
                    rs.getObject("valid_to", LocalDate.class)
                ), approver, versionId);
            if (assignments.isEmpty()) {
                throw new DomainConflictException(
                    "APPROVER_NOT_ELIGIBLE_IN_SCOPE:" + approver);
            }
            Map<String, Object> snapshot = Map.of(
                "eligible", true,
                "permission", DeliveryAuthorizationService.PLAN_APPROVE,
                "policyVersion", "F03-SOD-V1",
                "capturedAt", OffsetDateTime.now(clock).toString(),
                "assignments", assignments);
            jdbc.update("""
                UPDATE delivery_plan_approvers
                SET authority_snapshot = ?::jsonb
                WHERE plan_version_id = ? AND approver_subject = ?
                """, json(snapshot), versionId, approver);
        }
        int approverCount = configuredApprovers.size();
        PlanQuorum quorum = jdbc.query("""
            SELECT quorum_mode, quorum_required
            FROM delivery_plan_versions WHERE id = ?
            """, rs -> rs.next()
                ? new PlanQuorum(rs.getString(1), rs.getInt(2))
                : null, versionId);
        if (quorum == null || requiredApprovals(
            quorum.mode(), quorum.required(), approverCount) > approverCount) {
            throw new DomainConflictException(
                "APPROVAL_QUORUM_EXCEEDS_ELIGIBLE_AUTHORITY");
        }
    }

    private List<String> completenessBlockers(UUID versionId) {
        List<String> blockers = new ArrayList<>();
        RecipientView recipients = recipients(versionId);
        if (recipients.arrowFoundry().isEmpty()) {
            blockers.add("ARROWFOUNDRY_RECIPIENT_REQUIRED");
        }
        if (recipients.relianceStakeholders().isEmpty()) {
            blockers.add("RELIANCE_STAKEHOLDER_REQUIRED");
        }
        if (recipients.procurementCc().isEmpty()) {
            blockers.add("PROCUREMENT_CC_REQUIRED");
        }
        int deliverableCount = jdbc.queryForObject("""
            SELECT COUNT(*) FROM delivery_deliverable_versions WHERE plan_version_id = ?
            """, Integer.class, versionId);
        if (deliverableCount == 0) {
            blockers.add("DELIVERABLE_REQUIRED");
        }
        jdbc.query("""
            SELECT deliverable.id, stable.deliverable_code,
                   deliverable.dependency_none_declared,
                   deliverable.link_exception_reason,
                   (SELECT COUNT(*) FROM delivery_acceptance_criteria criterion
                    WHERE criterion.deliverable_version_id = deliverable.id) AS criteria_count,
                   (SELECT COUNT(*) FROM delivery_dependencies dependency
                    WHERE dependency.deliverable_version_id = deliverable.id) AS dependency_count,
                   (SELECT COUNT(*) FROM delivery_employee_assignments assignment
                    WHERE assignment.deliverable_version_id = deliverable.id) AS assignment_count,
                   (SELECT COUNT(*) FROM linear_issue_links link
                    JOIN linear_issue_current current
                      ON current.connection_id = link.connection_id
                     AND current.linear_issue_uuid = link.linear_issue_uuid
                    WHERE link.deliverable_version_id = deliverable.id
                      AND link.status = 'ACTIVE'
                      AND NOT current.stale
                      AND NOT current.inaccessible) AS link_count
            FROM delivery_deliverable_versions deliverable
            JOIN delivery_deliverables stable ON stable.id = deliverable.deliverable_id
            WHERE deliverable.plan_version_id = ?
            ORDER BY stable.deliverable_code
            """, rs -> {
                while (rs.next()) {
                    String code = rs.getString("deliverable_code");
                    if (rs.getInt("criteria_count") == 0) {
                        blockers.add(code + ":CRITERION_REQUIRED");
                    }
                    if (rs.getInt("assignment_count") == 0) {
                        blockers.add(code + ":ASSIGNMENT_REQUIRED");
                    }
                    if (!rs.getBoolean("dependency_none_declared")
                        && rs.getInt("dependency_count") == 0) {
                        blockers.add(code + ":DEPENDENCY_DECLARATION_REQUIRED");
                    }
                    if (rs.getInt("link_count") == 0
                        && (rs.getString("link_exception_reason") == null
                            || rs.getString("link_exception_reason").isBlank())) {
                        blockers.add(code + ":LINEAR_LINK_OR_EXCEPTION_REQUIRED");
                    } else if (rs.getInt("link_count") == 0
                        && rs.getString("link_exception_reason").trim().length() < 20) {
                        blockers.add(code + ":LINEAR_EXCEPTION_RATIONALE_TOO_SHORT");
                    }
                }
                return null;
            }, versionId);
        int invalidAssignments = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM delivery_employee_assignments assignment
            JOIN delivery_deliverable_versions deliverable
              ON deliverable.id = assignment.deliverable_version_id
            WHERE deliverable.plan_version_id = ?
              AND assignment.exception_reason IS NULL
              AND NOT EXISTS (
                  SELECT 1
                  FROM employee_project_allocations allocation
                  WHERE allocation.employee_id = assignment.employee_id
                    AND allocation.project_id = deliverable.project_id
                    AND allocation.status IN ('PLANNED', 'ACTIVE')
                    AND allocation.valid_from <= COALESCE(
                        assignment.effective_to, assignment.effective_from)
                    AND (allocation.valid_to IS NULL
                      OR allocation.valid_to >= assignment.effective_from)
              )
            """, Integer.class, versionId);
        if (invalidAssignments > 0) {
            blockers.add("ASSIGNMENT_ALLOCATION_INVALID");
        }
        int inactiveSubjects = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM (
                SELECT product_owner_subject AS subject
                FROM delivery_deliverable_versions WHERE plan_version_id = ?
                UNION
                SELECT vendor_owner_subject
                FROM delivery_deliverable_versions WHERE plan_version_id = ?
                UNION
                SELECT approver_subject
                FROM delivery_plan_approvers WHERE plan_version_id = ?
            ) configured
            WHERE NOT EXISTS (
                SELECT 1 FROM user_profiles profile
                WHERE profile.identity_subject = configured.subject
                  AND profile.status = 'ACTIVE'
            )
            """, Integer.class, versionId, versionId, versionId);
        if (inactiveSubjects > 0) {
            blockers.add("OWNER_OR_APPROVER_INACTIVE");
        }
        int outsideTargetMonth = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM delivery_deliverable_versions deliverable
            JOIN delivery_plan_versions version ON version.id = deliverable.plan_version_id
            JOIN delivery_plans plan ON plan.id = version.plan_id
            JOIN engagement_months month ON month.id = plan.engagement_month_id
            WHERE version.id = ?
              AND (
                  deliverable.target_completion_date < month.month_start_date
                  OR deliverable.target_completion_date >=
                     (month.month_start_date + INTERVAL '1 month')::date
              )
            """, Integer.class, versionId);
        if (outsideTargetMonth > 0) {
            blockers.add("TARGET_COMPLETION_OUTSIDE_ENGAGEMENT_MONTH");
        }
        int invalidInternalDependencies = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM delivery_dependencies dependency
            JOIN delivery_deliverable_versions source
              ON source.id = dependency.deliverable_version_id
            WHERE source.plan_version_id = ?
              AND dependency.dependency_type = 'INTERNAL'
              AND (
                  dependency.depends_on_deliverable_id IS NULL
                  OR NOT EXISTS (
                      SELECT 1 FROM delivery_deliverable_versions target
                      WHERE target.plan_version_id = source.plan_version_id
                        AND target.deliverable_id =
                            dependency.depends_on_deliverable_id
                  )
              )
            """, Integer.class, versionId);
        if (invalidInternalDependencies > 0) {
            blockers.add("INTERNAL_DEPENDENCY_OUTSIDE_PLAN");
        }
        int inactiveDependencyOwners = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM delivery_dependencies dependency
            JOIN delivery_deliverable_versions deliverable
              ON deliverable.id = dependency.deliverable_version_id
            WHERE deliverable.plan_version_id = ?
              AND NOT EXISTS (
                  SELECT 1 FROM user_profiles profile
                  WHERE profile.identity_subject = dependency.owner_subject
                    AND profile.status = 'ACTIVE'
              )
            """, Integer.class, versionId);
        if (inactiveDependencyOwners > 0) {
            blockers.add("DEPENDENCY_OWNER_INACTIVE");
        }
        ensureNoDependencyCycle(versionId);
        return blockers;
    }

    private void validatePlanRequest(CreatePlanRequest request) {
        if (!BASELINE_TYPES.contains(request.baselineType())) {
            throw new IllegalArgumentException("Unsupported baselineType.");
        }
        if (!QUORUM_MODES.contains(request.quorumMode())) {
            throw new IllegalArgumentException("Unsupported quorumMode.");
        }
        Set<String> approvers = new LinkedHashSet<>(request.approverSubjects());
        if (approvers.size() != request.approverSubjects().size()) {
            throw new IllegalArgumentException("Approvers must be unique.");
        }
        if ("ANY_ONE".equals(request.quorumMode()) && request.quorumRequired() != 1) {
            throw new IllegalArgumentException(
                "ANY_ONE quorumRequired must be 1.");
        }
        if ("ALL".equals(request.quorumMode())
            && request.quorumRequired() != approvers.size()) {
            throw new IllegalArgumentException(
                "ALL quorumRequired must equal the approver count.");
        }
        if (requiredApprovals(
            request.quorumMode(), request.quorumRequired(), approvers.size()) > approvers.size()) {
            throw new IllegalArgumentException("Quorum cannot exceed eligible approvers.");
        }
        Set<String> codes = new HashSet<>();
        for (DeliverableRequest deliverable : request.deliverables()) {
            if (!codes.add(deliverable.deliverableCode())) {
                throw new IllegalArgumentException("Deliverable codes must be unique.");
            }
            if (!DEPENDENCY_TYPES.containsAll(deliverable.dependencies().stream()
                .map(DependencyRequest::type).toList())) {
                throw new IllegalArgumentException("Unsupported dependency type.");
            }
            if (deliverable.dependencyNoneDeclared() && !deliverable.dependencies().isEmpty()) {
                throw new IllegalArgumentException(
                    "dependencyNoneDeclared conflicts with dependency records.");
            }
            for (AssignmentRequest assignment : deliverable.assignments()) {
                if (assignment.effectiveTo() != null
                    && assignment.effectiveTo().isBefore(assignment.effectiveFrom())) {
                    throw new IllegalArgumentException(
                        "Assignment end date cannot precede start date.");
                }
            }
        }
    }

    private void insertApprovers(UUID versionId, List<String> approvers) {
        for (String approver : approvers) {
            jdbc.update("""
                INSERT INTO delivery_plan_approvers
                    (plan_version_id, approver_subject, authority_snapshot)
                VALUES (?, ?, ?::jsonb)
                """, versionId, approver, "{}");
        }
    }

    private void insertRecipients(UUID versionId, List<String> arrow, List<String> reliance,
                                  List<String> procurement) {
        jdbc.update("""
            INSERT INTO delivery_recipient_snapshots
                (plan_version_id, arrow_foundry, reliance_stakeholders, procurement_cc)
            VALUES (?, ?::jsonb, ?::jsonb, ?::jsonb)
            """, versionId, json(arrow), json(reliance), json(procurement));
    }

    private void insertDeliverable(UUID planId, UUID versionId, DeliverableRequest request) {
        UUID stableId = UUID.randomUUID();
        UUID deliverableVersionId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO delivery_deliverables (id, plan_id, deliverable_code)
            VALUES (?, ?, ?)
            """, stableId, planId, request.deliverableCode());
        jdbc.update("""
            INSERT INTO delivery_deliverable_versions
                (id, deliverable_id, plan_version_id, project_id, title, description,
                 business_objective, product_owner_subject, vendor_owner_subject,
                 priority, target_completion_date, evidence_expectations,
                 dependency_none_declared, risk_and_assumptions, delivery_category,
                 link_exception_reason)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, deliverableVersionId, stableId, versionId, request.projectId(),
            request.title(), request.description(), request.businessObjective(),
            request.productOwnerSubject(), request.vendorOwnerSubject(), request.priority(),
            request.targetCompletionDate(), request.evidenceExpectations(),
            request.dependencyNoneDeclared(), request.riskAndAssumptions(),
            request.deliveryCategory(), request.linkExceptionReason());
        jdbc.update("""
            INSERT INTO delivery_execution_projections (deliverable_version_id)
            VALUES (?)
            """, deliverableVersionId);
        int sequence = 1;
        for (CriterionRequest criterion : request.criteria()) {
            jdbc.update("""
                INSERT INTO delivery_acceptance_criteria
                    (id, deliverable_version_id, sequence, statement,
                     validation_method, expected_result, mandatory)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), deliverableVersionId, sequence++,
                criterion.statement(), criterion.validationMethod(),
                criterion.expectedResult(), criterion.mandatory());
        }
        for (DependencyRequest dependency : request.dependencies()) {
            jdbc.update("""
                INSERT INTO delivery_dependencies
                    (id, deliverable_version_id, dependency_type,
                     depends_on_deliverable_id, description, owner_subject,
                     target_resolution_date, blocking)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), deliverableVersionId, dependency.type(),
                dependency.dependsOnDeliverableId(), dependency.description(),
                dependency.ownerSubject(), dependency.targetResolutionDate(),
                dependency.blocking());
        }
        for (AssignmentRequest assignment : request.assignments()) {
            jdbc.update("""
                INSERT INTO delivery_employee_assignments
                    (id, deliverable_version_id, employee_id, effective_from,
                     effective_to, exception_reason)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), deliverableVersionId, assignment.employeeId(),
                assignment.effectiveFrom(), assignment.effectiveTo(),
                assignment.exceptionReason());
        }
    }

    private void cloneDeliverables(UUID sourceVersionId, UUID targetVersionId, String subject) {
        List<CloneDeliverable> source = jdbc.query("""
            SELECT deliverable.id, deliverable.deliverable_id
            FROM delivery_deliverable_versions deliverable
            WHERE deliverable.plan_version_id = ?
            ORDER BY deliverable.id
            """, (rs, rowNum) -> new CloneDeliverable(
                rs.getObject("id", UUID.class),
                rs.getObject("deliverable_id", UUID.class)
            ), sourceVersionId);
        for (CloneDeliverable item : source) {
            UUID targetDeliverableVersionId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO delivery_deliverable_versions
                    (id, deliverable_id, plan_version_id, project_id, title,
                     description, business_objective, product_owner_subject,
                     vendor_owner_subject, priority, target_completion_date,
                     evidence_expectations, dependency_none_declared,
                     risk_and_assumptions, delivery_category, link_exception_reason)
                SELECT ?, deliverable_id, ?, project_id, title, description,
                       business_objective, product_owner_subject, vendor_owner_subject,
                       priority, target_completion_date, evidence_expectations,
                       dependency_none_declared, risk_and_assumptions, delivery_category,
                       link_exception_reason
                FROM delivery_deliverable_versions WHERE id = ?
                """, targetDeliverableVersionId, targetVersionId, item.sourceVersionId());
            jdbc.update("""
                INSERT INTO delivery_execution_projections
                    (deliverable_version_id, execution_projection)
                VALUES (?, 'UNKNOWN')
                """, targetDeliverableVersionId);
            jdbc.update("""
                INSERT INTO delivery_acceptance_criteria
                    (id, deliverable_version_id, sequence, statement,
                     validation_method, expected_result, mandatory)
                SELECT gen_random_uuid(), ?, sequence, statement,
                       validation_method, expected_result, mandatory
                FROM delivery_acceptance_criteria
                WHERE deliverable_version_id = ?
                """, targetDeliverableVersionId, item.sourceVersionId());
            jdbc.update("""
                INSERT INTO delivery_dependencies
                    (id, deliverable_version_id, dependency_type,
                     depends_on_deliverable_id, description, owner_subject,
                     target_resolution_date, blocking)
                SELECT gen_random_uuid(), ?, dependency_type,
                       depends_on_deliverable_id, description, owner_subject,
                       target_resolution_date, blocking
                FROM delivery_dependencies
                WHERE deliverable_version_id = ?
                """, targetDeliverableVersionId, item.sourceVersionId());
            jdbc.update("""
                INSERT INTO delivery_employee_assignments
                    (id, deliverable_version_id, employee_id, effective_from,
                     effective_to, exception_reason)
                SELECT gen_random_uuid(), ?, employee_id, effective_from,
                       effective_to, exception_reason
                FROM delivery_employee_assignments
                WHERE deliverable_version_id = ?
                """, targetDeliverableVersionId, item.sourceVersionId());
            jdbc.update("""
                INSERT INTO linear_issue_links
                    (id, deliverable_version_id, connection_id, linear_issue_uuid,
                     identifier, issue_url, multi_link_rationale, status,
                     created_by_subject)
                SELECT gen_random_uuid(), ?, connection_id, linear_issue_uuid,
                       identifier, issue_url, multi_link_rationale, status, ?
                FROM linear_issue_links
                WHERE deliverable_version_id = ?
                """, targetDeliverableVersionId, subject, item.sourceVersionId());
        }
    }

    private PlanView planView(UUID planId) {
        PlanRow row = jdbc.query("""
            SELECT plan.id, plan.engagement_month_id, plan.current_version_id,
                   version.version, version.state, version.title, version.summary,
                   version.business_outcomes, version.coordinator_subject,
                   version.baseline_type, version.checksum, version.prior_version_id,
                   version.revision_reason, version.revision_impact,
                   version.created_by_subject, version.created_at,
                   version.submitted_at, version.frozen_at,
                   baseline.id AS baseline_id, outbox.status AS commitment_status
            FROM delivery_plans plan
            JOIN delivery_plan_versions version ON version.id = plan.current_version_id
            LEFT JOIN delivery_plan_baselines baseline
              ON baseline.plan_version_id = version.id
            LEFT JOIN commitment_outbox outbox
              ON outbox.plan_version_id = version.id
            WHERE plan.id = ?
            """, rs -> rs.next()
                ? new PlanRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("engagement_month_id", UUID.class),
                    rs.getObject("current_version_id", UUID.class),
                    rs.getInt("version"),
                    rs.getString("state"),
                    rs.getString("title"),
                    rs.getString("summary"),
                    rs.getString("business_outcomes"),
                    rs.getString("coordinator_subject"),
                    rs.getString("baseline_type"),
                    rs.getString("checksum"),
                    rs.getObject("prior_version_id", UUID.class),
                    rs.getString("revision_reason"),
                    rs.getString("revision_impact"),
                    rs.getString("created_by_subject"),
                    rs.getObject("created_at", OffsetDateTime.class),
                    rs.getObject("submitted_at", OffsetDateTime.class),
                    rs.getObject("frozen_at", OffsetDateTime.class),
                    rs.getObject("baseline_id", UUID.class),
                    rs.getString("commitment_status"))
                : null, planId);
        if (row == null) {
            throw notFound();
        }
        List<String> blockers = "DRAFT".equals(row.state())
            ? completenessBlockers(row.currentVersionId()) : List.of();
        return new PlanView(
            row.id(), row.engagementMonthId(), row.currentVersionId(), row.version(),
            row.state(), row.title(), row.summary(), row.businessOutcomes(),
            row.coordinatorSubject(), row.baselineType(), row.checksum(),
            row.priorVersionId(), row.revisionReason(), row.revisionImpact(),
            row.createdBySubject(), row.createdAt(), row.submittedAt(), row.frozenAt(),
            blockers, recipients(row.currentVersionId()),
            deliverables(row.currentVersionId()), approvals(row.currentVersionId()),
            row.baselineId(), row.commitmentStatus());
    }

    private List<DeliverableView> deliverables(UUID versionId) {
        return jdbc.query("""
            SELECT stable.id, deliverable.id AS deliverable_version_id,
                   stable.deliverable_code, deliverable.title, deliverable.description,
                   deliverable.business_objective, deliverable.project_id,
                   deliverable.product_owner_subject, deliverable.vendor_owner_subject,
                   deliverable.priority, deliverable.target_completion_date,
                   deliverable.evidence_expectations,
                   deliverable.dependency_none_declared,
                   deliverable.risk_and_assumptions, deliverable.delivery_category,
                   deliverable.link_exception_reason,
                   COALESCE(projection.execution_projection, 'UNKNOWN')
                       AS execution_projection
            FROM delivery_deliverable_versions deliverable
            JOIN delivery_deliverables stable ON stable.id = deliverable.deliverable_id
            LEFT JOIN delivery_execution_projections projection
              ON projection.deliverable_version_id = deliverable.id
            WHERE deliverable.plan_version_id = ?
            ORDER BY stable.deliverable_code
            """, (rs, rowNum) -> {
                UUID deliverableVersionId =
                    rs.getObject("deliverable_version_id", UUID.class);
                return new DeliverableView(
                    rs.getObject("id", UUID.class),
                    deliverableVersionId,
                    rs.getString("deliverable_code"),
                    rs.getString("title"),
                    rs.getString("description"),
                    rs.getString("business_objective"),
                    rs.getObject("project_id", UUID.class),
                    rs.getString("product_owner_subject"),
                    rs.getString("vendor_owner_subject"),
                    rs.getString("priority"),
                    rs.getObject("target_completion_date", LocalDate.class),
                    rs.getString("evidence_expectations"),
                    rs.getBoolean("dependency_none_declared"),
                    rs.getString("risk_and_assumptions"),
                    rs.getString("delivery_category"),
                    rs.getString("link_exception_reason"),
                    rs.getString("execution_projection"),
                    criteria(deliverableVersionId),
                    dependencies(deliverableVersionId),
                    assignments(deliverableVersionId),
                    linear.linkViews(deliverableVersionId)
                );
            }, versionId);
    }

    private List<CriterionView> criteria(UUID deliverableVersionId) {
        return jdbc.query("""
            SELECT id, sequence, statement, validation_method, expected_result, mandatory
            FROM delivery_acceptance_criteria
            WHERE deliverable_version_id = ?
            ORDER BY sequence
            """, (rs, rowNum) -> new CriterionView(
                rs.getObject("id", UUID.class),
                rs.getInt("sequence"),
                rs.getString("statement"),
                rs.getString("validation_method"),
                rs.getString("expected_result"),
                rs.getBoolean("mandatory")
            ), deliverableVersionId);
    }

    private List<DependencyView> dependencies(UUID deliverableVersionId) {
        return jdbc.query("""
            SELECT id, dependency_type, depends_on_deliverable_id, description,
                   owner_subject, target_resolution_date, blocking
            FROM delivery_dependencies
            WHERE deliverable_version_id = ?
            ORDER BY id
            """, (rs, rowNum) -> new DependencyView(
                rs.getObject("id", UUID.class),
                rs.getString("dependency_type"),
                rs.getObject("depends_on_deliverable_id", UUID.class),
                rs.getString("description"),
                rs.getString("owner_subject"),
                rs.getObject("target_resolution_date", LocalDate.class),
                rs.getBoolean("blocking")
            ), deliverableVersionId);
    }

    private List<AssignmentView> assignments(UUID deliverableVersionId) {
        return jdbc.query("""
            SELECT id, employee_id, effective_from, effective_to, exception_reason
            FROM delivery_employee_assignments
            WHERE deliverable_version_id = ?
            ORDER BY employee_id, effective_from
            """, (rs, rowNum) -> new AssignmentView(
                rs.getObject("id", UUID.class),
                rs.getObject("employee_id", UUID.class),
                rs.getObject("effective_from", LocalDate.class),
                rs.getObject("effective_to", LocalDate.class),
                rs.getString("exception_reason")
            ), deliverableVersionId);
    }

    private List<ApprovalView> approvals(UUID versionId) {
        return jdbc.query("""
            SELECT id, approver_subject, decision, signed_checksum, comment, decided_at
            FROM delivery_plan_approvals
            WHERE plan_version_id = ?
            ORDER BY decided_at
            """, (rs, rowNum) -> new ApprovalView(
                rs.getObject("id", UUID.class),
                rs.getString("approver_subject"),
                rs.getString("decision"),
                rs.getString("signed_checksum"),
                rs.getString("comment"),
                rs.getObject("decided_at", OffsetDateTime.class)
            ), versionId);
    }

    private RecipientView recipients(UUID versionId) {
        return jdbc.query("""
            SELECT arrow_foundry, reliance_stakeholders, procurement_cc
            FROM delivery_recipient_snapshots
            WHERE plan_version_id = ?
            """, rs -> {
                if (!rs.next()) {
                    return new RecipientView(List.of(), List.of(), List.of());
                }
                return new RecipientView(
                    stringList(rs.getString("arrow_foundry")),
                    stringList(rs.getString("reliance_stakeholders")),
                    stringList(rs.getString("procurement_cc"))
                );
            }, versionId);
    }

    private String checksum(UUID versionId) {
        StringBuilder canonical = new StringBuilder();
        appendCanonical(canonical, "schema", "delivery-commitment-v2");
        jdbc.query("""
            SELECT version.version, version.title, version.summary,
                   version.business_outcomes, version.coordinator_subject,
                   version.baseline_type, version.quorum_mode, version.quorum_required
            FROM delivery_plan_versions version WHERE version.id = ?
            """, rs -> {
                if (!rs.next()) {
                    throw notFound();
                }
                for (int column = 1; column <= 8; column++) {
                    appendCanonical(canonical, "plan." + column, rs.getString(column));
                }
                return null;
            }, versionId);
        jdbc.query("""
            SELECT approver_subject, authority_snapshot::text
            FROM delivery_plan_approvers
            WHERE plan_version_id = ?
            ORDER BY approver_subject
            """, rs -> {
                while (rs.next()) {
                    appendCanonical(canonical, "approver.subject", rs.getString(1));
                    appendCanonical(canonical, "approver.authority", rs.getString(2));
                }
                return null;
            }, versionId);
        deliverables(versionId).stream()
            .sorted(Comparator.comparing(DeliverableView::deliverableCode))
            .forEach(deliverable -> {
                appendCanonical(canonical, "deliverable.code", deliverable.deliverableCode());
                appendCanonical(canonical, "deliverable.stableId", deliverable.id());
                appendCanonical(canonical, "deliverable.versionId",
                    deliverable.deliverableVersionId());
                appendCanonical(canonical, "deliverable.title", deliverable.title());
                appendCanonical(canonical, "deliverable.description", deliverable.description());
                appendCanonical(canonical, "deliverable.objective",
                    deliverable.businessObjective());
                appendCanonical(canonical, "deliverable.project", deliverable.projectId());
                appendCanonical(canonical, "deliverable.productOwner",
                    deliverable.productOwnerSubject());
                appendCanonical(canonical, "deliverable.vendorOwner",
                    deliverable.vendorOwnerSubject());
                appendCanonical(canonical, "deliverable.priority", deliverable.priority());
                appendCanonical(canonical, "deliverable.target",
                    deliverable.targetCompletionDate());
                appendCanonical(canonical, "deliverable.evidence",
                    deliverable.evidenceExpectations());
                appendCanonical(canonical, "deliverable.noDependencies",
                    deliverable.dependencyNoneDeclared());
                appendCanonical(canonical, "deliverable.risks",
                    deliverable.riskAndAssumptions());
                appendCanonical(canonical, "deliverable.category",
                    deliverable.deliveryCategory());
                appendCanonical(canonical, "deliverable.linkException",
                    deliverable.linkExceptionReason());
                deliverable.criteria().stream()
                    .sorted(Comparator.comparingInt(CriterionView::sequence))
                    .forEach(criterion -> {
                        appendCanonical(canonical, "criterion.sequence",
                            criterion.sequence());
                        appendCanonical(canonical, "criterion.statement",
                            criterion.statement());
                        appendCanonical(canonical, "criterion.validation",
                            criterion.validationMethod());
                        appendCanonical(canonical, "criterion.expected",
                            criterion.expectedResult());
                        appendCanonical(canonical, "criterion.mandatory",
                            criterion.mandatory());
                    });
                deliverable.dependencies().stream()
                    .sorted(Comparator
                        .comparing(DependencyView::type)
                        .thenComparing(value -> String.valueOf(
                            value.dependsOnDeliverableId()))
                        .thenComparing(DependencyView::description))
                    .forEach(dependency -> {
                        appendCanonical(canonical, "dependency.type", dependency.type());
                        appendCanonical(canonical, "dependency.target",
                            dependency.dependsOnDeliverableId());
                        appendCanonical(canonical, "dependency.description",
                            dependency.description());
                        appendCanonical(canonical, "dependency.owner",
                            dependency.ownerSubject());
                        appendCanonical(canonical, "dependency.date",
                            dependency.targetResolutionDate());
                        appendCanonical(canonical, "dependency.blocking",
                            dependency.blocking());
                    });
                deliverable.assignments().stream()
                    .sorted(Comparator.comparing(AssignmentView::employeeId)
                        .thenComparing(AssignmentView::effectiveFrom))
                    .forEach(assignment -> {
                        appendCanonical(canonical, "assignment.employee",
                            assignment.employeeId());
                        appendCanonical(canonical, "assignment.from",
                            assignment.effectiveFrom());
                        appendCanonical(canonical, "assignment.to",
                            assignment.effectiveTo());
                        appendCanonical(canonical, "assignment.exception",
                            assignment.exceptionReason());
                    });
                deliverable.linearLinks().stream()
                    .sorted(Comparator.comparing(value -> value.issueUuid().toString()))
                    .forEach(link -> {
                        appendCanonical(canonical, "link.id", link.id());
                        appendCanonical(canonical, "link.connection", link.connectionId());
                        appendCanonical(canonical, "link.issue", link.issueUuid());
                        appendCanonical(canonical, "link.identifier", link.identifier());
                        appendCanonical(canonical, "link.url", link.url());
                        appendCanonical(canonical, "link.status", link.status());
                        appendCanonical(canonical, "link.rationale", link.rationale());
                    });
            });
        RecipientView recipient = recipients(versionId);
        recipient.arrowFoundry().stream().sorted()
            .forEach(value -> appendCanonical(canonical, "recipient.arrow", value));
        recipient.relianceStakeholders().stream().sorted()
            .forEach(value -> appendCanonical(canonical, "recipient.reliance", value));
        recipient.procurementCc().stream().sorted()
            .forEach(value -> appendCanonical(canonical, "recipient.procurement", value));
        jdbc.query("""
            SELECT id, issue_link_id, snapshot_type, status, provider_state::text,
                   normalized_state, fetched_at, payload_hash, confidence,
                   failure_reason
            FROM linear_issue_snapshots
            WHERE plan_version_id = ?
            ORDER BY issue_link_id, snapshot_type, id
            """, rs -> {
                while (rs.next()) {
                    for (int column = 1; column <= 10; column++) {
                        appendCanonical(
                            canonical, "snapshot." + column, rs.getObject(column));
                    }
                }
                return null;
            }, versionId);
        return sha256(canonical.toString());
    }

    private void appendCanonical(StringBuilder target, String field, Object value) {
        String text = value == null ? "<null>" : String.valueOf(value);
        target.append(field.length()).append(':').append(field)
            .append('=').append(text.length()).append(':').append(text).append('\n');
    }

    private String commitmentPlainText(UUID versionId, String checksum) {
        StringBuilder value = new StringBuilder("Approved delivery plan\nChecksum: ")
            .append(checksum).append("\n");
        for (DeliverableView deliverable : deliverables(versionId)) {
            value.append("\n").append(deliverable.deliverableCode()).append(": ")
                .append(deliverable.title()).append("\nOwner: ")
                .append(deliverable.productOwnerSubject()).append(" / ")
                .append(deliverable.vendorOwnerSubject()).append("\nTarget: ")
                .append(deliverable.targetCompletionDate()).append("\nAcceptance:\n");
            deliverable.criteria().forEach(criterion ->
                value.append("- ").append(criterion.statement()).append("\n"));
            deliverable.linearLinks().forEach(link ->
                value.append("- Linear ").append(link.identifier()).append("\n"));
        }
        return value.toString();
    }

    private void ensureNoDependencyCycle(UUID versionId) {
        Boolean cycle = jdbc.queryForObject("""
            WITH RECURSIVE edges AS (
                SELECT deliverable.deliverable_id AS source_id,
                       dependency.depends_on_deliverable_id AS target_id
                FROM delivery_deliverable_versions deliverable
                JOIN delivery_dependencies dependency
                  ON dependency.deliverable_version_id = deliverable.id
                WHERE deliverable.plan_version_id = ?
                  AND dependency.depends_on_deliverable_id IS NOT NULL
            ),
            walk(source_id, target_id, path, cycle) AS (
                SELECT source_id, target_id, ARRAY[source_id, target_id],
                       source_id = target_id
                FROM edges
                UNION ALL
                SELECT walk.source_id, edge.target_id,
                       walk.path || edge.target_id,
                       edge.target_id = ANY(walk.path)
                FROM walk
                JOIN edges edge ON edge.source_id = walk.target_id
                WHERE NOT walk.cycle
            )
            SELECT EXISTS (SELECT 1 FROM walk WHERE cycle)
            """, Boolean.class, versionId);
        if (Boolean.TRUE.equals(cycle)) {
            throw new DomainConflictException("Deliverable dependencies contain a cycle.");
        }
    }

    private CurrentVersion currentVersionForUpdate(UUID planId) {
        CurrentVersion current = jdbc.query("""
            SELECT version.id, version.version, version.state, version.checksum,
                   version.quorum_mode, version.quorum_required
            FROM delivery_plans plan
            JOIN delivery_plan_versions version ON version.id = plan.current_version_id
            WHERE plan.id = ?
            FOR UPDATE OF plan, version
            """, rs -> rs.next()
                ? new CurrentVersion(
                    rs.getObject("id", UUID.class),
                    rs.getInt("version"),
                    rs.getString("state"),
                    rs.getString("checksum"),
                    rs.getString("quorum_mode"),
                    rs.getInt("quorum_required"))
                : null, planId);
        if (current == null) {
            throw notFound();
        }
        return current;
    }

    private int requiredApprovals(String mode, int configured, int approverCount) {
        return switch (mode) {
            case "ANY_ONE" -> 1;
            case "ALL" -> approverCount;
            case "N_OF_M" -> configured;
            default -> throw new IllegalArgumentException("Unsupported quorum mode.");
        };
    }

    private void lockMonth(UUID engagementMonthId) {
        Integer result = jdbc.query("""
            SELECT 1 FROM engagement_months WHERE id = ? FOR UPDATE
            """, rs -> rs.next() ? rs.getInt(1) : null, engagementMonthId);
        if (result == null) {
            throw notFound();
        }
    }

    private void audit(UUID planId, UUID versionId, String eventType, String actor,
                       String facts) {
        jdbc.update("""
            INSERT INTO delivery_audit_events
                (id, plan_id, plan_version_id, event_type, actor_subject, facts)
            VALUES (?, ?, ?, ?, ?, ?::jsonb)
            """, UUID.randomUUID(), planId, versionId, eventType, actor, facts);
    }

    private List<String> stringList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored recipient JSON is invalid.", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize domain data.", exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private String escapeHtml(String value) {
        return value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    private EntityNotFoundException notFound() {
        return new EntityNotFoundException("Resource not found.");
    }

    private record CurrentVersion(
        UUID versionId,
        int version,
        String state,
        String checksum,
        String quorumMode,
        int quorumRequired
    ) {
    }

    private record PlanRow(
        UUID id,
        UUID engagementMonthId,
        UUID currentVersionId,
        int version,
        String state,
        String title,
        String summary,
        String businessOutcomes,
        String coordinatorSubject,
        String baselineType,
        String checksum,
        UUID priorVersionId,
        String revisionReason,
        String revisionImpact,
        String createdBySubject,
        OffsetDateTime createdAt,
        OffsetDateTime submittedAt,
        OffsetDateTime frozenAt,
        UUID baselineId,
        String commitmentStatus
    ) {
    }

    private record SnapshotSource(
        UUID linkId,
        String stateId,
        String stateName,
        String stateType,
        String stateCategory,
        String normalizedState,
        OffsetDateTime fetchedAt,
        String payloadHash
    ) {
    }

    private record ProviderState(String id, String name, String type, String category) {
    }

    private record AuthorityAssignment(
        UUID assignmentId,
        String roleCode,
        UUID organizationId,
        String scopeType,
        UUID scopeId,
        LocalDate validFrom,
        LocalDate validTo
    ) {
    }

    private record PlanQuorum(String mode, int required) {
    }

    private record CloneDeliverable(UUID sourceVersionId, UUID stableId) {
    }
}
