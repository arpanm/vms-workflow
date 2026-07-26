package com.vms.workflow.application;

import com.vms.workflow.api.CertificationDtos.AttendanceExceptionInput;
import com.vms.workflow.api.CertificationDtos.AttendanceExceptionView;
import com.vms.workflow.api.CertificationDtos.EvidenceExceptionInput;
import com.vms.workflow.api.CertificationDtos.EvidenceExceptionView;
import com.vms.workflow.api.CertificationDtos.PolicyVersionInput;
import com.vms.workflow.api.CertificationDtos.PolicyVersionView;
import com.vms.workflow.api.DomainConflictException;
import com.vms.workflow.infrastructure.CorrelationIdFilter;
import com.vms.workflow.security.CertificationAuthorizationService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class CertificationPolicyService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final CertificationAuthorizationService authorization;
    private final CanonicalEvidenceHasher hasher;

    public CertificationPolicyService(
        JdbcTemplate jdbc,
        ObjectMapper objectMapper,
        CertificationAuthorizationService authorization,
        CanonicalEvidenceHasher hasher
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.authorization = authorization;
        this.hasher = hasher;
    }

    @Transactional
    public PolicyVersionView createPolicyVersion(
        String subject,
        UUID monthId,
        PolicyVersionInput input,
        String ifMatch,
        String idempotencyKey
    ) {
        authorization.requireClientOrProcurement(
            subject, monthId, CertificationAuthorizationService.CLOSE);
        requireIdempotencyKey(idempotencyKey);
        long expected = expectedVersion(ifMatch);
        requireMatchingVersion(expected, input.expectedMonthVersion());
        validatePolicy(input);
        String requestHash = requestHash(
            "f04-policy-version-request-v1", input);
        UUID prior = priorResult(
            subject, "CREATE_POLICY_VERSION", monthId,
            idempotencyKey, requestHash);
        if (prior != null) {
            return policyView(prior);
        }

        MonthScope month = lockMonth(monthId);
        requireVersion(month.version(), expected);
        PolicyLineage active = jdbc.query("""
            SELECT id, version
            FROM certification_policy_versions
            WHERE engagement_id = ? AND status = 'ACTIVE'
            FOR UPDATE
            """, rs -> rs.next() ? new PolicyLineage(
                rs.getObject("id", UUID.class), rs.getInt("version")) : null,
            month.engagementId());
        int version = active == null ? 1 : active.version() + 1;
        if (active != null) {
            jdbc.update("""
                UPDATE certification_policy_versions
                SET status = 'SUPERSEDED'
                WHERE id = ? AND status = 'ACTIVE'
                """, active.id());
        }

        List<Integer> reminders = input.reminderOffsetsSeconds().stream()
            .distinct().sorted().toList();
        Map<String, Object> reminderPolicy = Map.of(
            "offsetsSeconds", reminders,
            "reviewSlaSeconds", input.reviewSlaSeconds());
        Map<String, Object> evidencePolicy = Map.of(
            "requireWhenFrozenExpectationPresent",
            input.evidenceRequiredWhenFrozenExpectationPresent(),
            "allowedScanStatuses",
            input.allowedScanStatuses().stream().distinct().sorted().toList(),
            "authorizedExceptionRequired", true);
        Map<String, Object> recipientPolicy =
            Map.of("source", input.recipientSource());
        Map<String, Object> retentionPolicy = Map.of(
            "retentionDays", input.retentionDays(),
            "legalHoldSupported", true);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schema", "certification-policy-v2");
        manifest.put("engagementId", month.engagementId());
        manifest.put("version", version);
        manifest.put("attendanceRequired", input.attendanceRequired());
        manifest.put(
            "separationOfDutiesRequired",
            input.separationOfDutiesRequired());
        manifest.put(
            "monthlyDecisionRequired", input.monthlyDecisionRequired());
        manifest.put(
            "manualSecondReviewRequired",
            input.manualSecondReviewRequired());
        manifest.put("deemedApprovalsEnabled", false);
        manifest.put("quorumMode", input.quorumMode());
        manifest.put("quorumRequired", input.quorumRequired());
        manifest.put("tokenTtlSeconds", input.tokenTtlSeconds());
        manifest.put(
            "confirmationDueSeconds", input.confirmationDueSeconds());
        manifest.put("reminderPolicy", reminderPolicy);
        manifest.put("evidencePolicy", evidencePolicy);
        manifest.put("recipientPolicy", recipientPolicy);
        manifest.put("retentionPolicy", retentionPolicy);
        var hash = hasher.hash(manifest);
        UUID policyId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO certification_policy_versions
                (id, engagement_id, version, status, attendance_required,
                 separation_of_duties_required, monthly_decision_required,
                 manual_second_review_required,
                 deemed_submission_approval_enabled,
                 deemed_certification_approval_enabled,
                 deemed_confirmation_approval_enabled,
                 quorum_mode, quorum_required, token_ttl_seconds,
                 confirmation_due_seconds, reminder_policy,
                 evidence_policy, recipient_policy, retention_policy,
                 policy_hash, hash_algorithm, hash_schema_version,
                 created_by_subject)
            VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?, ?,
                    FALSE, FALSE, FALSE, ?, ?, ?, ?,
                    ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb,
                    ?, 'SHA-256', 1, ?)
            """, policyId, month.engagementId(), version,
            input.attendanceRequired(),
            input.separationOfDutiesRequired(),
            input.monthlyDecisionRequired(),
            input.manualSecondReviewRequired(),
            input.quorumMode(), input.quorumRequired(),
            input.tokenTtlSeconds(), input.confirmationDueSeconds(),
            json(reminderPolicy), json(evidencePolicy),
            json(recipientPolicy), json(retentionPolicy),
            hash.checksum(), subject);
        UUID correlationId = CorrelationIdFilter.currentOrNew();
        audit(
            monthId, "CERTIFICATION_POLICY_VERSION_CREATED", subject,
            "certification_policy_version", policyId, version,
            "Additive policy version created; prior active version superseded",
            "ACTIVE", correlationId);
        event(
            monthId, "certification.policy.version-created.v1", subject,
            "certification_policy_version", policyId, version,
            correlationId, Map.of(
                "policyHash", hash.checksum(),
                "supersedesPolicyId",
                active == null ? "" : active.id().toString()));
        recordIdempotency(
            subject, "CREATE_POLICY_VERSION", monthId, idempotencyKey,
            requestHash, "certification_policy_version", policyId);
        bumpMonth(monthId);
        return policyView(policyId);
    }

    @Transactional
    public EvidenceExceptionView approveEvidenceException(
        String subject,
        UUID submissionId,
        EvidenceExceptionInput input,
        String ifMatch,
        String idempotencyKey
    ) {
        SubmissionScope initial = submissionScope(
            submissionId, input.deliverableId(), input.criterionId(), false);
        authorization.requireClientOrProcurement(
            subject, initial.monthId(),
            CertificationAuthorizationService.CLOSE);
        requireIdempotencyKey(idempotencyKey);
        long expected = expectedVersion(ifMatch);
        requireMatchingVersion(expected, input.expectedMonthVersion());
        String requestHash = requestHash(
            "f04-evidence-exception-request-v1", input);
        UUID prior = priorResult(
            subject, "APPROVE_EVIDENCE_EXCEPTION", submissionId,
            idempotencyKey, requestHash);
        if (prior != null) {
            return evidenceExceptionView(prior);
        }
        MonthScope month = lockMonth(initial.monthId());
        requireVersion(month.version(), expected);
        SubmissionScope scope = submissionScope(
            submissionId, input.deliverableId(), input.criterionId(), true);
        if (!"DRAFT".equals(scope.status())) {
            throw new DomainConflictException(
                "SUBMISSION_LOCKED",
                "Evidence exceptions must be approved before submission.");
        }
        if (subject.equals(scope.createdBySubject())) {
            throw new DomainConflictException(
                "EVIDENCE_EXCEPTION_SEPARATION_OF_DUTIES_REQUIRED",
                "The submission author cannot approve its evidence exception.");
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1
                FROM certification_evidence_exceptions exception
                WHERE exception.submission_id = ?
                  AND exception.deliverable_version_id = ?
                  AND exception.criterion_id IS NOT DISTINCT FROM ?::uuid
            )
            """, Boolean.class, submissionId, input.deliverableId(),
            input.criterionId()))) {
            throw new DomainConflictException(
                "EVIDENCE_EXCEPTION_ALREADY_EXISTS",
                "An immutable exception already covers this evidence scope.");
        }
        UUID exceptionId = UUID.randomUUID();
        UUID correlationId = CorrelationIdFilter.currentOrNew();
        jdbc.update("""
            INSERT INTO certification_evidence_exceptions
                (id, engagement_month_id, submission_id,
                 deliverable_version_id, criterion_id, reason_code,
                 justification, authority_snapshot, approved_by_subject,
                 correlation_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            """, exceptionId, scope.monthId(), submissionId,
            input.deliverableId(), input.criterionId(),
            input.reasonCode(), input.justification(),
            json(Map.of(
                "permission", CertificationAuthorizationService.CLOSE,
                "resolvedServerSide", true,
                "separatedFromSubmissionAuthor", true)),
            subject, correlationId);
        audit(
            scope.monthId(), "CERTIFICATION_EVIDENCE_EXCEPTION_APPROVED",
            subject, "certification_evidence_exception", exceptionId, 1,
            input.justification(), "APPROVED", correlationId);
        event(
            scope.monthId(), "certification.evidence-exception.approved.v1",
            subject, "certification_evidence_exception", exceptionId, 1,
            correlationId, Map.of(
                "submissionId", submissionId,
                "deliverableId", input.deliverableId(),
                "criterionId",
                input.criterionId() == null
                    ? "" : input.criterionId().toString(),
                "reasonCode", input.reasonCode()));
        recordIdempotency(
            subject, "APPROVE_EVIDENCE_EXCEPTION", submissionId,
            idempotencyKey, requestHash,
            "certification_evidence_exception", exceptionId);
        bumpMonth(scope.monthId());
        return evidenceExceptionView(exceptionId);
    }

    @Transactional
    public AttendanceExceptionView approveAttendanceException(
        String subject,
        UUID monthId,
        AttendanceExceptionInput input,
        String ifMatch,
        String idempotencyKey
    ) {
        authorization.requireClientOrProcurement(
            subject, monthId, CertificationAuthorizationService.CLOSE);
        requireIdempotencyKey(idempotencyKey);
        long expected = expectedVersion(ifMatch);
        requireMatchingVersion(expected, input.expectedMonthVersion());
        String requestHash = requestHash(
            "f04-attendance-exception-request-v1", input);
        UUID prior = priorResult(
            subject, "APPROVE_ATTENDANCE_EXCEPTION", monthId,
            idempotencyKey, requestHash);
        if (prior != null) {
            return attendanceExceptionView(prior);
        }
        MonthScope month = lockMonth(monthId);
        requireVersion(month.version(), expected);
        UUID policyId = jdbc.query("""
            SELECT id
            FROM certification_policy_versions
            WHERE engagement_id = ? AND status = 'ACTIVE'
            FOR UPDATE
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
            month.engagementId());
        if (policyId == null) {
            throw new DomainConflictException(
                "CERTIFICATION_POLICY_REQUIRED",
                "An active certification policy is required.");
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1
                FROM attendance_snapshot_versions snapshot
                WHERE snapshot.engagement_month_id = ?
                  AND snapshot.status = 'CLOSED'
                  AND NOT EXISTS (
                      SELECT 1
                      FROM attendance_snapshot_versions newer
                      WHERE newer.supersedes_id = snapshot.id
                  )
            )
            """, Boolean.class, monthId))) {
            throw new DomainConflictException(
                "ATTENDANCE_SNAPSHOT_AVAILABLE",
                "A current closed attendance snapshot already exists.");
        }
        UUID exceptionId = UUID.randomUUID();
        UUID correlationId = CorrelationIdFilter.currentOrNew();
        List<String> disclosures = input.disclosures().stream()
            .map(String::strip)
            .collect(java.util.stream.Collectors.collectingAndThen(
                java.util.stream.Collectors.toCollection(LinkedHashSet::new),
                List::copyOf));
        jdbc.update("""
            INSERT INTO certification_attendance_exceptions
                (id, engagement_month_id, policy_version_id,
                 reason_code, justification, disclosure_manifest,
                 authority_snapshot, approved_by_subject, correlation_id)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
            """, exceptionId, monthId, policyId,
            input.reasonCode(), input.justification(),
            json(Map.of(
                "schema", "f04-attendance-exception-v1",
                "disclosures", disclosures)),
            json(Map.of(
                "permission", CertificationAuthorizationService.CLOSE,
                "resolvedServerSide", true)),
            subject, correlationId);
        audit(
            monthId, "CERTIFICATION_ATTENDANCE_EXCEPTION_APPROVED",
            subject, "certification_attendance_exception", exceptionId, 1,
            input.justification(), "APPROVED", correlationId);
        event(
            monthId, "certification.attendance-exception.approved.v1",
            subject, "certification_attendance_exception", exceptionId, 1,
            correlationId, Map.of(
                "policyVersionId", policyId,
                "reasonCode", input.reasonCode(),
                "disclosureCount", disclosures.size()));
        recordIdempotency(
            subject, "APPROVE_ATTENDANCE_EXCEPTION", monthId,
            idempotencyKey, requestHash,
            "certification_attendance_exception", exceptionId);
        bumpMonth(monthId);
        return attendanceExceptionView(exceptionId);
    }

    private void validatePolicy(PolicyVersionInput input) {
        if (!input.separationOfDutiesRequired()
            || !input.manualSecondReviewRequired()) {
            throw new IllegalArgumentException(
                "Separation of duties and manual second review are mandatory.");
        }
        if ("ANY_ONE".equals(input.quorumMode())
            && input.quorumRequired() != 1) {
            throw new IllegalArgumentException(
                "ANY_ONE requires configured quorum value 1.");
        }
        List<Integer> reminders = input.reminderOffsetsSeconds().stream()
            .distinct().toList();
        if (reminders.size() != input.reminderOffsetsSeconds().size()
            || reminders.stream().anyMatch(value ->
                value >= input.confirmationDueSeconds())) {
            throw new IllegalArgumentException(
                "Reminder offsets must be unique and earlier than the due interval.");
        }
        Set<String> scans = new LinkedHashSet<>(
            input.allowedScanStatuses());
        if (scans.size() != input.allowedScanStatuses().size()
            || !Set.of("PASSED", "NOT_REQUIRED").containsAll(scans)) {
            throw new IllegalArgumentException(
                "Allowed scan statuses are invalid or duplicated.");
        }
    }

    private SubmissionScope submissionScope(
        UUID submissionId,
        UUID deliverableId,
        UUID criterionId,
        boolean lock
    ) {
        SubmissionScope scope = jdbc.query("""
            SELECT submission.engagement_month_id,
                   submission.policy_version_id, submission.status,
                   submission.created_by_subject
            FROM delivery_submissions submission
            JOIN deliverable_delivery_outcomes outcome
              ON outcome.submission_id = submission.id
             AND outcome.deliverable_version_id = ?
            WHERE submission.id = ?
              AND (
                  ?::uuid IS NULL
                  OR EXISTS (
                      SELECT 1
                      FROM delivery_acceptance_criteria criterion
                      WHERE criterion.id = ?::uuid
                        AND criterion.deliverable_version_id =
                            outcome.deliverable_version_id
                  )
              )
            """ + (lock ? " FOR UPDATE OF submission" : ""),
            rs -> rs.next() ? new SubmissionScope(
                rs.getObject("engagement_month_id", UUID.class),
                rs.getObject("policy_version_id", UUID.class),
                rs.getString("status"),
                rs.getString("created_by_subject")) : null,
            deliverableId, submissionId, criterionId, criterionId);
        if (scope == null) {
            throw notFound();
        }
        return scope;
    }

    private MonthScope lockMonth(UUID monthId) {
        MonthScope month = jdbc.query("""
            SELECT engagement_id, certification_version
            FROM engagement_months
            WHERE id = ?
            FOR UPDATE
            """, rs -> rs.next() ? new MonthScope(
                rs.getObject("engagement_id", UUID.class),
                rs.getLong("certification_version")) : null,
            monthId);
        if (month == null) {
            throw notFound();
        }
        return month;
    }

    private PolicyVersionView policyView(UUID id) {
        PolicyVersionView view = jdbc.query("""
            SELECT policy.id, policy.engagement_id, policy.version,
                   policy.status, policy.quorum_mode,
                   policy.quorum_required, policy.token_ttl_seconds,
                   policy.confirmation_due_seconds,
                   policy.reminder_policy::text, policy.policy_hash,
                   policy.created_at, policy.created_by_subject,
                   profile.display_name
            FROM certification_policy_versions policy
            LEFT JOIN user_profiles profile
              ON profile.identity_subject = policy.created_by_subject
            WHERE policy.id = ?
            """, rs -> rs.next() ? new PolicyVersionView(
                rs.getObject("id", UUID.class),
                rs.getObject("engagement_id", UUID.class),
                rs.getInt("version"), rs.getString("status"),
                rs.getString("quorum_mode"),
                rs.getInt("quorum_required"),
                rs.getInt("token_ttl_seconds"),
                rs.getInt("confirmation_due_seconds"),
                integerList(
                    map(rs.getString("reminder_policy"))
                        .get("offsetsSeconds")),
                rs.getString("policy_hash"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getString("display_name") == null
                    ? rs.getString("created_by_subject")
                    : rs.getString("display_name")) : null,
            id);
        if (view == null) {
            throw notFound();
        }
        return view;
    }

    private EvidenceExceptionView evidenceExceptionView(UUID id) {
        EvidenceExceptionView view = jdbc.query("""
            SELECT exception.id, exception.engagement_month_id,
                   exception.submission_id,
                   exception.deliverable_version_id,
                   exception.criterion_id, exception.reason_code,
                   exception.justification, exception.approved_by_subject,
                   profile.display_name, exception.approved_at,
                   exception.correlation_id
            FROM certification_evidence_exceptions exception
            LEFT JOIN user_profiles profile
              ON profile.identity_subject = exception.approved_by_subject
            WHERE exception.id = ?
            """, rs -> rs.next() ? new EvidenceExceptionView(
                rs.getObject("id", UUID.class),
                rs.getObject("engagement_month_id", UUID.class),
                rs.getObject("submission_id", UUID.class),
                rs.getObject("deliverable_version_id", UUID.class),
                rs.getObject("criterion_id", UUID.class),
                rs.getString("reason_code"),
                rs.getString("justification"),
                rs.getString("display_name") == null
                    ? rs.getString("approved_by_subject")
                    : rs.getString("display_name"),
                rs.getObject("approved_at", OffsetDateTime.class),
                rs.getObject("correlation_id", UUID.class)) : null,
            id);
        if (view == null) {
            throw notFound();
        }
        return view;
    }

    private AttendanceExceptionView attendanceExceptionView(UUID id) {
        AttendanceExceptionView view = jdbc.query("""
            SELECT exception.id, exception.engagement_month_id,
                   exception.policy_version_id, exception.reason_code,
                   exception.justification,
                   exception.disclosure_manifest::text,
                   exception.approved_by_subject, profile.display_name,
                   exception.approved_at, exception.correlation_id
            FROM certification_attendance_exceptions exception
            LEFT JOIN user_profiles profile
              ON profile.identity_subject = exception.approved_by_subject
            WHERE exception.id = ?
            """, rs -> rs.next() ? new AttendanceExceptionView(
                rs.getObject("id", UUID.class),
                rs.getObject("engagement_month_id", UUID.class),
                rs.getObject("policy_version_id", UUID.class),
                rs.getString("reason_code"),
                rs.getString("justification"),
                stringList(
                    map(rs.getString("disclosure_manifest"))
                        .get("disclosures")),
                rs.getString("display_name") == null
                    ? rs.getString("approved_by_subject")
                    : rs.getString("display_name"),
                rs.getObject("approved_at", OffsetDateTime.class),
                rs.getObject("correlation_id", UUID.class)) : null,
            id);
        if (view == null) {
            throw notFound();
        }
        return view;
    }

    private void audit(
        UUID monthId,
        String type,
        String actor,
        String objectType,
        UUID objectId,
        int version,
        String reason,
        String result,
        UUID correlationId
    ) {
        jdbc.update("""
            INSERT INTO certification_audit_events
                (id, engagement_month_id, event_type, actor_subject,
                 authority_snapshot, object_type, object_id, object_version,
                 source, reason, result, correlation_id)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, 'IN_APP', ?, ?, ?)
            """, UUID.randomUUID(), monthId, type, actor,
            json(Map.of(
                "permission", CertificationAuthorizationService.CLOSE,
                "resolvedServerSide", true)),
            objectType, objectId, version, reason, result, correlationId);
    }

    private void event(
        UUID monthId,
        String type,
        String actor,
        String subjectType,
        UUID subjectId,
        int version,
        UUID correlationId,
        Map<String, ?> payload
    ) {
        jdbc.update("""
            INSERT INTO certification_domain_events
                (id, engagement_month_id, event_type, actor_type,
                 actor_subject, subject_type, subject_id, subject_version,
                 correlation_id, payload)
            VALUES (?, ?, ?, 'USER', ?, ?, ?, ?, ?, ?::jsonb)
            """, UUID.randomUUID(), monthId, type, actor,
            subjectType, subjectId, version, correlationId,
            hasher.hash(payload).canonicalJson());
    }

    private UUID priorResult(
        String actor,
        String operation,
        UUID scopeId,
        String key,
        String requestHash
    ) {
        return jdbc.query("""
            SELECT request_hash, result_id
            FROM certification_idempotency_keys
            WHERE actor_subject = ? AND operation = ?
              AND scope_id = ? AND idempotency_key = ?
            """, rs -> {
                if (!rs.next()) {
                    return null;
                }
                if (!requestHash.equals(rs.getString("request_hash"))) {
                    throw new DomainConflictException(
                        "IDEMPOTENCY_KEY_REUSED",
                        "The idempotency key was already used with different input.");
                }
                return rs.getObject("result_id", UUID.class);
            }, actor, operation, scopeId, key);
    }

    private void recordIdempotency(
        String actor,
        String operation,
        UUID scopeId,
        String key,
        String requestHash,
        String resultType,
        UUID resultId
    ) {
        jdbc.update("""
            INSERT INTO certification_idempotency_keys
                (id, actor_subject, operation, scope_id, idempotency_key,
                 request_hash, result_type, result_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), actor, operation, scopeId, key,
            requestHash, resultType, resultId);
    }

    private String requestHash(String schema, Object input) {
        return hasher.hash(Map.of(
            "schema", schema,
            "request", input)).checksum();
    }

    private long expectedVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new IllegalArgumentException("If-Match is required.");
        }
        String value = ifMatch.strip();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        if (value.length() >= 2
            && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                "If-Match must contain a numeric version.", exception);
        }
    }

    private void requireMatchingVersion(long header, long body) {
        if (header != body) {
            throw new IllegalArgumentException(
                "If-Match and expected version must match.");
        }
    }

    private void requireVersion(long current, long expected) {
        if (current != expected) {
            throw new DomainConflictException(
                "MONTH_VERSION_CONFLICT",
                "The engagement month version is stale.", current);
        }
    }

    private void requireIdempotencyKey(String value) {
        if (value == null || value.isBlank() || value.length() > 160) {
            throw new IllegalArgumentException(
                "A valid Idempotency-Key is required.");
        }
    }

    private void bumpMonth(UUID monthId) {
        jdbc.update("""
            UPDATE engagement_months
            SET certification_version = certification_version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """, monthId);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                "Unable to serialize certification policy data.", exception);
        }
    }

    private Map<String, Object> map(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {
            });
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                "Stored certification policy JSON is invalid.", exception);
        }
    }

    private List<Integer> integerList(Object raw) {
        if (!(raw instanceof List<?> values)) {
            return List.of();
        }
        List<Integer> result = new ArrayList<>();
        values.forEach(value -> {
            if (value instanceof Number number) {
                result.add(number.intValue());
            }
        });
        return List.copyOf(result);
    }

    private List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().map(String::valueOf).toList();
    }

    private EntityNotFoundException notFound() {
        return new EntityNotFoundException("Resource not found.");
    }

    private record MonthScope(UUID engagementId, long version) {
    }

    private record PolicyLineage(UUID id, int version) {
    }

    private record SubmissionScope(
        UUID monthId,
        UUID policyId,
        String status,
        String createdBySubject
    ) {
    }
}
