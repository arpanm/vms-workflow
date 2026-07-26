package com.vms.workflow.application;

import com.vms.workflow.api.CertificationDtos.ReadinessBlocker;
import com.vms.workflow.api.CertificationDtos.ReadinessPillar;
import com.vms.workflow.api.CertificationDtos.ReadinessView;
import com.vms.workflow.infrastructure.CorrelationIdFilter;
import com.vms.workflow.security.CertificationAuthorizationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CertificationReadinessService {
    private final JdbcTemplate jdbc;
    private final CertificationAuthorizationService authorization;
    private final CanonicalEvidenceHasher hasher;
    private final CertificationConfiguration configuration;
    private final Clock clock;

    public CertificationReadinessService(
        JdbcTemplate jdbc,
        CertificationAuthorizationService authorization,
        CanonicalEvidenceHasher hasher,
        CertificationConfiguration configuration,
        Clock clock
    ) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.hasher = hasher;
        this.configuration = configuration;
        this.clock = clock;
    }

    @Transactional
    public ReadinessView evaluate(String subject, UUID monthId) {
        authorization.requireMonthRead(subject, monthId);
        return evaluateAuthorized(subject, monthId);
    }

    @Transactional
    public ReadinessView evaluateAuthorized(String subject, UUID monthId) {
        ReadinessSources sources = sources(monthId);
        OffsetDateTime evaluatedAt = OffsetDateTime.now(clock);
        List<ReadinessPillar> pillars = new ArrayList<>();

        List<ReadinessBlocker> rosterBlockers = new ArrayList<>();
        if (sources.rosterCount() == 0) {
            rosterBlockers.add(blocker(
                "ROSTER_ALLOCATION_MISSING",
                "No frozen-plan workforce allocation is available.",
                "Vendor delivery manager", "Review plan allocations",
                "/delivery/plans"));
        }
        pillars.add(pillar("ROSTER", "Roster and allocation",
            rosterBlockers, sources.planVersionId(), evaluatedAt));

        List<ReadinessBlocker> attendanceBlockers = new ArrayList<>();
        if (sources.attendanceRequired()
            && sources.attendanceSnapshotId() == null
            && sources.attendanceExceptionId() == null) {
            attendanceBlockers.add(blocker(
                "CLOSED_ATTENDANCE_SNAPSHOT_REQUIRED",
                "A current closed attendance snapshot or authorized disclosed exception is required.",
                "Attendance close authority", "Close attendance",
                "/attendance/month-close"));
        }
        pillars.add(pillar("ATTENDANCE", "Attendance",
            attendanceBlockers,
            sources.attendanceSnapshotId() == null
                ? sources.attendanceExceptionId()
                : sources.attendanceSnapshotId(),
            evaluatedAt));

        List<ReadinessBlocker> planBlockers = new ArrayList<>();
        if (sources.planVersionId() == null || sources.baselineId() == null
            || !"FROZEN".equals(sources.planState())) {
            planBlockers.add(blocker(
                "EFFECTIVE_FROZEN_PLAN_REQUIRED",
                "The effective delivery plan is not frozen with a baseline.",
                "Delivery governance", "Resolve delivery plan",
                "/delivery/plans"));
        }
        if (sources.pendingRevision()) {
            planBlockers.add(blocker(
                "PLAN_REVISION_PENDING",
                "A delivery-plan revision is still pending.",
                "Delivery governance", "Resolve plan revision",
                "/delivery/plans"));
        }
        if (sources.linearAttemptCount() < sources.deliverableCount()) {
            planBlockers.add(blocker(
                "LINEAR_MONTH_END_STATUS_MISSING",
                "Every baseline item requires captured, failed or unavailable month-end Linear status.",
                "Delivery integration owner", "Review Linear evidence",
                "/delivery/integration-health"));
        }
        pillars.add(pillar("PLAN_LINEAR", "Plan and Linear",
            planBlockers, sources.planVersionId(), evaluatedAt));

        List<ReadinessBlocker> certificationBlockers = new ArrayList<>();
        if (sources.submissionId() == null || sources.submissionChecksum() == null) {
            certificationBlockers.add(blocker(
                "DELIVERY_SUBMISSION_REQUIRED",
                "A complete locked vendor delivery submission is required.",
                "Vendor delivery manager", "Complete submission",
                "/certification/" + monthId));
        }
        if (sources.summaryId() == null) {
            certificationBlockers.add(blocker(
                "CERTIFICATION_SUMMARY_REQUIRED",
                "Terminal item decisions and an explicit monthly certification summary are required.",
                "Client product owner", "Complete certification",
                "/certification/" + monthId + "/review"));
        }
        if (sources.activeConfirmerCount() == 0) {
            certificationBlockers.add(blocker(
                "ACTIVE_ELIGIBLE_CONFIRMER_REQUIRED",
                "No active scoped eligible confirmer can be resolved.",
                "Engagement administrator", "Configure confirmer authority",
                "/certification/" + monthId + "/review"));
        }
        if (!sources.recipientsComplete()) {
            certificationBlockers.add(blocker(
                "RECIPIENT_CATEGORY_MISSING",
                "Vendor, client and Central Procurement recipient groups are required.",
                "Engagement administrator", "Configure recipients",
                "/confirmation/" + monthId));
        }
        pillars.add(pillar("CERTIFICATION", "Certification",
            certificationBlockers, sources.summaryId(), evaluatedAt));

        List<ReadinessBlocker> confirmationBlockers = new ArrayList<>();
        if (!"CONFIRMED".equals(sources.confirmationStatus())) {
            confirmationBlockers.add(blocker(
                sources.confirmationRequestId() == null
                    ? "CONFIRMATION_REQUEST_REQUIRED"
                    : "VERIFIED_CONFIRMATION_REQUIRED",
                "An explicit verified confirmation quorum is required; transport, silence and receipts do not approve.",
                "Eligible client confirmer", "Review confirmation",
                sources.confirmationRequestId() == null
                    ? "/confirmation/" + monthId
                    : "/confirmation/requests/"
                        + sources.confirmationRequestId()));
        }
        if (sources.activeInvalidationCount() > 0) {
            confirmationBlockers.add(blocker(
                "DOWNSTREAM_FACT_INVALIDATED",
                "A correction or reopen invalidated readiness facts.",
                "Delivery governance", "Resolve invalidations",
                "/certification/" + monthId));
        }
        if (!"CONFIGURED".equals(configuration.f05HandoffStatus())) {
            confirmationBlockers.add(new ReadinessBlocker(
                "F05_HANDOFF_NOT_CONFIGURED",
                "The F05 package/invoice consumer is external and not configured.",
                "INFORMATION", "Procurement integration owner",
                "Configure F05 consumer", null));
        }
        pillars.add(pillar("CONFIRMATION_HANDOFF", "Confirmation and handoff",
            confirmationBlockers, sources.confirmationRequestId(), evaluatedAt));

        boolean readyForRequest = rosterBlockers.isEmpty()
            && attendanceBlockers.isEmpty()
            && planBlockers.isEmpty()
            && certificationBlockers.isEmpty();
        boolean f04Ready = readyForRequest
            && confirmationBlockers.stream().noneMatch(
                blocker -> !"F05_HANDOFF_NOT_CONFIGURED".equals(blocker.code()));
        List<ReadinessBlocker> allBlockers = pillars.stream()
            .flatMap(value -> value.blockers().stream())
            .toList();
        String status = f04Ready ? "READY"
            : readyForRequest ? "ACTION_REQUIRED" : "BLOCKED";
        String f05Status = sources.activeInvalidationCount() > 0
            ? "INVALIDATED"
            : f04Ready && "CONFIGURED".equals(configuration.f05HandoffStatus())
                ? "ELIGIBLE" : "NOT_ELIGIBLE";

        Map<String, Object> manifest = sourceManifest(sources);
        CanonicalEvidenceHasher.HashResult inputHash = hasher.hash(manifest);
        UUID runId = jdbc.query("""
            SELECT id FROM certification_readiness_runs
            WHERE engagement_month_id = ? AND input_hash = ?
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
            monthId, inputHash.checksum());
        if (runId == null) {
            runId = UUID.randomUUID();
            UUID correlationId = CorrelationIdFilter.currentOrNew();
            jdbc.update("""
                INSERT INTO certification_readiness_runs
                    (id, engagement_month_id, input_manifest, input_hash, status,
                     ready_for_confirmation_request, ready_for_f05_handoff,
                     evaluated_by_subject, correlation_id)
                VALUES (?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)
                """, runId, monthId, inputHash.canonicalJson(),
                inputHash.checksum(),
                f04Ready ? "READY_FOR_F05"
                    : readyForRequest ? "READY_FOR_REQUEST" : "BLOCKED",
                readyForRequest, f04Ready, subject, correlationId);
            for (ReadinessPillar value : pillars) {
                ReadinessBlocker first = value.blockers().isEmpty()
                    ? null : value.blockers().getFirst();
                jdbc.update("""
                    INSERT INTO certification_readiness_results
                        (id, run_id, pillar, status, source_object_type,
                         source_object_id, source_version, freshness,
                         blocker_code, severity, owner_role, action_cta, details)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    """, UUID.randomUUID(), runId, storedPillar(value.key()),
                    storedPillarStatus(value.status()), "F04_OR_UPSTREAM_FACT",
                    uuidOrNull(value.sourceVersionId()), value.sourceVersionId(),
                    value.freshness(), first == null ? null : first.code(),
                    first == null ? "INFO" : storedSeverity(first.severity()),
                    first == null ? null : first.owner(),
                    first == null ? null : first.actionLabel(),
                    json(Map.of("blockers", value.blockers())));
            }
        }
        return new ReadinessView(
            monthId, sources.monthVersion(), "f04-readiness-v1:"
                + inputHash.checksum(), status, evaluatedAt, false,
            pillars, allBlockers, f05Status);
    }

    public boolean readyForConfirmationRequest(ReadinessView view) {
        return view.pillars().stream()
            .filter(pillar -> !"CONFIRMATION_HANDOFF".equals(pillar.key()))
            .allMatch(pillar -> "READY".equals(pillar.status()));
    }

    private ReadinessSources sources(UUID monthId) {
        return jdbc.query("""
            SELECT month.id AS month_id,
                   month.certification_version,
                   COALESCE(
                       policy.attendance_required,
                       engagement.attendance_required)
                       AS attendance_required,
                   plan.current_version_id AS plan_version_id,
                   version.state AS plan_state,
                   baseline.id AS baseline_id,
                   baseline.checksum AS baseline_checksum,
                   submission.id AS submission_id,
                   submission.checksum AS submission_checksum,
                   policy.id AS policy_version_id,
                   summary.id AS summary_id,
                   summary.version AS summary_version,
                   summary.checksum AS summary_checksum,
                   confirmation.id AS confirmation_id,
                   confirmation.version AS confirmation_version,
                   confirmation.status AS confirmation_status,
                   confirmation.scope_checksum AS confirmation_scope_checksum,
                   confirmation.due_at AS confirmation_due_at,
                   (SELECT COUNT(*)
                    FROM delivery_deliverable_versions deliverable
                    WHERE deliverable.plan_version_id = plan.current_version_id)
                       AS deliverable_count,
                   (SELECT COUNT(*)
                    FROM delivery_employee_assignments assignment
                    JOIN delivery_deliverable_versions deliverable
                      ON deliverable.id = assignment.deliverable_version_id
                    WHERE deliverable.plan_version_id = plan.current_version_id)
                       AS roster_count,
                   (SELECT COUNT(*)
                    FROM deliverable_delivery_outcomes outcome
                    WHERE outcome.submission_id = submission.id
                      AND outcome.linear_month_end_status IN (
                          'CAPTURED', 'FETCH_FAILED', 'UNAVAILABLE'))
                       AS linear_attempt_count,
                   (SELECT snapshot.id
                    FROM attendance_snapshot_versions snapshot
                    WHERE snapshot.engagement_month_id = month.id
                      AND snapshot.status = 'CLOSED'
                      AND NOT EXISTS (
                          SELECT 1 FROM attendance_snapshot_versions newer
                          WHERE newer.supersedes_id = snapshot.id)
                    ORDER BY snapshot.version DESC LIMIT 1)
                       AS attendance_snapshot_id,
                   (SELECT exception.id
                    FROM certification_attendance_exceptions exception
                    WHERE exception.engagement_month_id = month.id
                      AND exception.policy_version_id = policy.id
                    ORDER BY exception.approved_at DESC LIMIT 1)
                       AS attendance_exception_id,
                   EXISTS (
                       SELECT 1 FROM delivery_plan_versions pending
                       WHERE pending.plan_id = plan.id
                         AND pending.id <> plan.current_version_id
                         AND pending.state IN (
                             'DRAFT', 'READY_FOR_REVIEW', 'PENDING_APPROVAL',
                             'CHANGES_REQUESTED')
                   ) AS pending_revision,
                   (SELECT COUNT(DISTINCT deliverable.product_owner_subject)
                    FROM delivery_deliverable_versions deliverable
                    JOIN user_profiles profile
                     ON profile.identity_subject =
                         deliverable.product_owner_subject
                     AND profile.status = 'ACTIVE'
                     AND profile.principal_type = 'HUMAN'
                    WHERE deliverable.plan_version_id = plan.current_version_id)
                       AS active_confirmer_count,
                   (recipient.arrow_foundry IS NOT NULL
                    AND jsonb_array_length(recipient.arrow_foundry) > 0
                    AND jsonb_array_length(recipient.reliance_stakeholders) > 0
                    AND jsonb_array_length(recipient.procurement_cc) > 0)
                       AS recipients_complete,
                   (SELECT COUNT(*)
                    FROM effective_certification_invalidations invalidation
                    WHERE invalidation.engagement_month_id = month.id
                      AND invalidation.effective_status = 'ACTIVE')
                       AS active_invalidation_count
            FROM engagement_months month
            JOIN engagements engagement ON engagement.id = month.engagement_id
            LEFT JOIN delivery_plans plan ON plan.engagement_month_id = month.id
            LEFT JOIN delivery_plan_versions version
              ON version.id = plan.current_version_id
            LEFT JOIN delivery_plan_baselines baseline
              ON baseline.plan_version_id = plan.current_version_id
            LEFT JOIN delivery_recipient_snapshots recipient
              ON recipient.plan_version_id = plan.current_version_id
            LEFT JOIN delivery_submissions submission
              ON submission.engagement_month_id = month.id
             AND submission.status IN ('SUBMITTED', 'UNDER_REVIEW')
            LEFT JOIN certification_policy_versions policy
              ON policy.id = COALESCE(
                  submission.policy_version_id,
                  (
                      SELECT active.id
                      FROM certification_policy_versions active
                      WHERE active.engagement_id = month.engagement_id
                        AND active.status = 'ACTIVE'
                  )
              )
            LEFT JOIN monthly_certification_summaries summary
              ON summary.engagement_month_id = month.id
             AND summary.status = 'CURRENT'
            LEFT JOIN business_confirmation_requests confirmation
              ON confirmation.engagement_month_id = month.id
             AND confirmation.status NOT IN ('CANCELLED', 'SUPERSEDED', 'EXPIRED')
            WHERE month.id = ?
            """, rs -> {
                if (!rs.next()) {
                    throw new jakarta.persistence.EntityNotFoundException(
                        "Resource not found.");
                }
                return new ReadinessSources(
                    rs.getObject("month_id", UUID.class),
                    rs.getLong("certification_version"),
                    rs.getBoolean("attendance_required"),
                    rs.getObject("plan_version_id", UUID.class),
                    rs.getString("plan_state"),
                    rs.getObject("baseline_id", UUID.class),
                    rs.getString("baseline_checksum"),
                    rs.getObject("submission_id", UUID.class),
                    rs.getString("submission_checksum"),
                    rs.getObject("policy_version_id", UUID.class),
                    rs.getObject("summary_id", UUID.class),
                    (Integer) rs.getObject("summary_version"),
                    rs.getString("summary_checksum"),
                    rs.getObject("attendance_snapshot_id", UUID.class),
                    rs.getObject("attendance_exception_id", UUID.class),
                    rs.getInt("deliverable_count"), rs.getInt("roster_count"),
                    rs.getInt("linear_attempt_count"),
                    rs.getBoolean("pending_revision"),
                    rs.getInt("active_confirmer_count"),
                    rs.getBoolean("recipients_complete"),
                    rs.getObject("confirmation_id", UUID.class),
                    (Integer) rs.getObject("confirmation_version"),
                    rs.getString("confirmation_status"),
                    rs.getString("confirmation_scope_checksum"),
                    rs.getObject("confirmation_due_at", OffsetDateTime.class),
                    rs.getInt("active_invalidation_count"));
            }, monthId);
    }

    private Map<String, Object> sourceManifest(ReadinessSources sources) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schema", "f04-readiness-input-v1");
        value.put("monthVersion", sources.monthVersion());
        value.put("attendanceRequired", sources.attendanceRequired());
        value.put("planVersionId", string(sources.planVersionId()));
        value.put("planState", sources.planState());
        value.put("baselineId", string(sources.baselineId()));
        value.put("baselineChecksum", sources.baselineChecksum());
        value.put("deliverableCount", sources.deliverableCount());
        value.put("rosterAllocationCount", sources.rosterCount());
        value.put("linearMonthEndAttemptCount", sources.linearAttemptCount());
        value.put("pendingPlanRevision", sources.pendingRevision());
        value.put("submissionId", string(sources.submissionId()));
        value.put("submissionChecksum", sources.submissionChecksum());
        value.put("policyVersionId", string(sources.policyVersionId()));
        value.put("summaryId", string(sources.summaryId()));
        value.put("summaryVersion", sources.summaryVersion());
        value.put("summaryChecksum", sources.summaryChecksum());
        value.put("attendanceSnapshotId", string(sources.attendanceSnapshotId()));
        value.put("attendanceExceptionId", string(sources.attendanceExceptionId()));
        value.put("activeConfirmerCount", sources.activeConfirmerCount());
        value.put("recipientsComplete", sources.recipientsComplete());
        value.put("confirmationRequestId", string(sources.confirmationRequestId()));
        value.put("confirmationVersion", sources.confirmationVersion());
        value.put("confirmationStatus", sources.confirmationStatus());
        value.put("confirmationScopeChecksum",
            sources.confirmationScopeChecksum());
        value.put("confirmationDueAt", sources.confirmationDueAt());
        value.put("activeInvalidations", sources.activeInvalidationCount());
        value.put(
            "f05HandoffConfigurationStatus",
            configuration.f05HandoffStatus());
        value.put(
            "attendanceExceptionManifest",
            attendanceExceptionManifest(sources.attendanceExceptionId()));
        value.put(
            "activeInvalidationManifest",
            activeInvalidationManifest(sources.monthId()));
        value.put("rosterManifest", rosterManifest(sources.planVersionId()));
        value.put("deliverableLinearManifest",
            deliverableLinearManifest(
                sources.planVersionId(), sources.submissionId()));
        value.put("recipientManifestHash",
            recipientManifestHash(sources.planVersionId()));
        value.put("confirmerManifest",
            confirmerManifest(sources.planVersionId()));
        return value;
    }

    private Map<String, Object> attendanceExceptionManifest(
        UUID exceptionId
    ) {
        if (exceptionId == null) {
            return Map.of();
        }
        return jdbc.query("""
            SELECT id, policy_version_id, reason_code,
                   justification, disclosure_manifest::text,
                   approved_by_subject, approved_at
            FROM certification_attendance_exceptions
            WHERE id = ?
            """, rs -> {
                if (!rs.next()) {
                    return Map.of();
                }
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("id", rs.getObject("id", UUID.class));
                value.put(
                    "policyVersionId",
                    rs.getObject("policy_version_id", UUID.class));
                value.put("reasonCode", rs.getString("reason_code"));
                value.put(
                    "justificationHash",
                    hasher.sha256(rs.getString("justification")));
                value.put(
                    "disclosureManifestHash",
                    hasher.sha256(rs.getString("disclosure_manifest")));
                value.put(
                    "approvedBySubjectHash",
                    hasher.sha256(rs.getString("approved_by_subject")));
                value.put(
                    "approvedAt",
                    rs.getObject("approved_at", OffsetDateTime.class));
                return value;
            }, exceptionId);
    }

    private List<Map<String, Object>> activeInvalidationManifest(
        UUID monthId
    ) {
        return jdbc.query("""
            SELECT id, object_type, object_id, reason_code,
                   downstream_contract, created_at
            FROM effective_certification_invalidations
            WHERE engagement_month_id = ?
              AND effective_status = 'ACTIVE'
            ORDER BY id
            """, (rs, rowNum) -> {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("id", rs.getObject("id", UUID.class));
                value.put("objectType", rs.getString("object_type"));
                value.put("objectId", rs.getObject("object_id", UUID.class));
                value.put("reasonCode", rs.getString("reason_code"));
                value.put(
                    "downstreamContract",
                    rs.getString("downstream_contract"));
                value.put(
                    "createdAt",
                    rs.getObject("created_at", OffsetDateTime.class));
                return value;
            }, monthId);
    }

    private List<Map<String, Object>> rosterManifest(UUID planVersionId) {
        if (planVersionId == null) {
            return List.of();
        }
        return jdbc.query("""
            SELECT assignment.id, assignment.deliverable_version_id,
                   assignment.employee_id, assignment.effective_from,
                   assignment.effective_to
            FROM delivery_employee_assignments assignment
            JOIN delivery_deliverable_versions deliverable
              ON deliverable.id = assignment.deliverable_version_id
            WHERE deliverable.plan_version_id = ?
            ORDER BY assignment.id
            """, (rs, rowNum) -> {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("assignmentId",
                    rs.getObject("id", UUID.class).toString());
                value.put("deliverableVersionId",
                    rs.getObject("deliverable_version_id", UUID.class).toString());
                value.put("employeeId",
                    rs.getObject("employee_id", UUID.class).toString());
                value.put("effectiveFrom",
                    String.valueOf(rs.getObject("effective_from")));
                value.put("effectiveTo",
                    String.valueOf(rs.getObject("effective_to")));
                return value;
            }, planVersionId);
    }

    private List<Map<String, Object>> deliverableLinearManifest(
        UUID planVersionId,
        UUID submissionId
    ) {
        if (planVersionId == null) {
            return List.of();
        }
        return jdbc.query("""
            SELECT deliverable.id,
                   outcome.linear_month_end_status,
                   (SELECT COUNT(*)
                    FROM linear_issue_links link
                    WHERE link.deliverable_version_id = deliverable.id)
                       AS issue_link_count
            FROM delivery_deliverable_versions deliverable
            LEFT JOIN deliverable_delivery_outcomes outcome
              ON outcome.deliverable_version_id = deliverable.id
             AND outcome.submission_id = ?::uuid
            WHERE deliverable.plan_version_id = ?
            ORDER BY deliverable.id
            """, (rs, rowNum) -> {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("deliverableVersionId",
                    rs.getObject("id", UUID.class).toString());
                value.put("linearMonthEndStatus",
                    rs.getString("linear_month_end_status"));
                value.put("issueLinkCount", rs.getInt("issue_link_count"));
                return value;
            }, submissionId, planVersionId);
    }

    private String recipientManifestHash(UUID planVersionId) {
        if (planVersionId == null) {
            return null;
        }
        String recipient = jdbc.query("""
            SELECT jsonb_build_object(
                'vendor', arrow_foundry,
                'client', reliance_stakeholders,
                'procurement', procurement_cc
            )::text
            FROM delivery_recipient_snapshots
            WHERE plan_version_id = ?
            """, rs -> rs.next() ? rs.getString(1) : null, planVersionId);
        return recipient == null ? null : hasher.sha256(recipient);
    }

    private List<Map<String, Object>> confirmerManifest(UUID planVersionId) {
        if (planVersionId == null) {
            return List.of();
        }
        return jdbc.query("""
            SELECT DISTINCT deliverable.product_owner_subject,
                   deliverable.project_id, profile.status,
                   profile.principal_type
            FROM delivery_deliverable_versions deliverable
            LEFT JOIN user_profiles profile
              ON profile.identity_subject = deliverable.product_owner_subject
            WHERE deliverable.plan_version_id = ?
            ORDER BY deliverable.product_owner_subject, deliverable.project_id
            """, (rs, rowNum) -> Map.of(
                "subjectHash", hasher.sha256(
                    rs.getString("product_owner_subject")),
                "projectId", rs.getObject("project_id", UUID.class).toString(),
                "status", String.valueOf(rs.getString("status")),
                "principalType",
                String.valueOf(rs.getString("principal_type"))),
            planVersionId);
    }

    private ReadinessPillar pillar(
        String key,
        String label,
        List<ReadinessBlocker> blockers,
        UUID sourceId,
        OffsetDateTime checkedAt
    ) {
        return new ReadinessPillar(
            key, label, blockers.isEmpty() ? "READY" : "BLOCKED",
            string(sourceId), sourceId == null ? "UNKNOWN" : "CURRENT",
            checkedAt, List.copyOf(blockers));
    }

    private ReadinessBlocker blocker(
        String code,
        String message,
        String owner,
        String action,
        String path
    ) {
        return new ReadinessBlocker(
            code, message, "BLOCKING", owner, action, path);
    }

    private String storedPillar(String key) {
        return switch (key) {
            case "ROSTER" -> "ROSTER_ALLOCATION";
            case "CONFIRMATION_HANDOFF" -> "CONFIRMATION_F05";
            default -> key;
        };
    }

    private String storedPillarStatus(String status) {
        return "STALE".equals(status) ? "ACTION_REQUIRED" : status;
    }

    private String storedSeverity(String severity) {
        return "INFORMATION".equals(severity) ? "INFO" : severity;
    }

    private UUID uuidOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String string(UUID value) {
        return value == null ? null : value.toString();
    }

    private String json(Object value) {
        try {
            return new tools.jackson.databind.ObjectMapper()
                .writeValueAsString(value);
        } catch (tools.jackson.core.JacksonException exception) {
            throw new IllegalStateException("Unable to serialize readiness facts.", exception);
        }
    }

    record ReadinessSources(
        UUID monthId,
        long monthVersion,
        boolean attendanceRequired,
        UUID planVersionId,
        String planState,
        UUID baselineId,
        String baselineChecksum,
        UUID submissionId,
        String submissionChecksum,
        UUID policyVersionId,
        UUID summaryId,
        Integer summaryVersion,
        String summaryChecksum,
        UUID attendanceSnapshotId,
        UUID attendanceExceptionId,
        int deliverableCount,
        int rosterCount,
        int linearAttemptCount,
        boolean pendingRevision,
        int activeConfirmerCount,
        boolean recipientsComplete,
        UUID confirmationRequestId,
        Integer confirmationVersion,
        String confirmationStatus,
        String confirmationScopeChecksum,
        OffsetDateTime confirmationDueAt,
        int activeInvalidationCount
    ) {
    }
}
