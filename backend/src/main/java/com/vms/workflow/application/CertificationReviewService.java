package com.vms.workflow.application;

import com.vms.workflow.api.CertificationDtos.InboundMessageReviewInput;
import com.vms.workflow.api.CertificationDtos.InboundMessageRecordInput;
import com.vms.workflow.api.CertificationDtos.InboundReviewView;
import com.vms.workflow.api.CertificationDtos.ManualEvidenceRecordInput;
import com.vms.workflow.api.CertificationDtos.ManualEvidenceReviewInput;
import com.vms.workflow.api.DomainConflictException;
import com.vms.workflow.infrastructure.CorrelationIdFilter;
import com.vms.workflow.security.CertificationAuthorizationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class CertificationReviewService {
    private static final int DEFAULT_REVIEW_SLA_SECONDS = 86_400;

    private final JdbcTemplate jdbc;
    private final CertificationAuthorizationService authorization;
    private final CanonicalEvidenceHasher hasher;
    private final InboundMessageAuthenticator inboundAuthenticator;
    private final CertificationSecurityEventService securityEvents;
    private final BusinessConfirmationService confirmations;
    private final Clock clock;

    public CertificationReviewService(
        JdbcTemplate jdbc,
        CertificationAuthorizationService authorization,
        CanonicalEvidenceHasher hasher,
        InboundMessageAuthenticator inboundAuthenticator,
        CertificationSecurityEventService securityEvents,
        @Lazy BusinessConfirmationService confirmations,
        Clock clock
    ) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.hasher = hasher;
        this.inboundAuthenticator = inboundAuthenticator;
        this.securityEvents = securityEvents;
        this.confirmations = confirmations;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<InboundReviewView> items(
        String subject,
        UUID monthId,
        boolean authorized
    ) {
        if (!authorized) {
            return List.of();
        }
        authorization.requireInboundReview(subject, monthId);
        int reviewSlaSeconds = reviewSlaSeconds(monthId);
        List<InboundReviewView> values = new ArrayList<>();
        inboundRows(monthId, null, false).stream()
            .map(row -> inboundView(row, reviewSlaSeconds))
            .forEach(values::add);
        manualRows(monthId, null, false).stream()
            .map(row -> manualView(row, reviewSlaSeconds))
            .forEach(values::add);
        return values.stream()
            .sorted(Comparator.comparing(
                InboundReviewView::recordedAt,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();
    }

    @Transactional
    public InboundReviewView recordInbound(
        String subject,
        UUID monthId,
        InboundMessageRecordInput input,
        String ifMatch,
        String idempotencyKey,
        long signatureTimestamp,
        String signature
    ) {
        authorization.requireInboundIngest(subject, monthId);
        requireIdempotencyKey(idempotencyKey);
        long expected = requireMatchingMonthVersion(
            ifMatch, input.expectedMonthVersion());
        if (!inboundAuthenticator.configured()) {
            throw new DomainConflictException(
                "INBOUND_ADAPTER_NOT_CONFIGURED",
                "The inbound confirmation adapter is not configured.");
        }
        if (!inboundAuthenticator.verify(
                monthId, signatureTimestamp, signature, input)) {
            securityEvents.recordBestEffort(
                monthId, "INBOUND_CALLBACK_REJECTED", subject,
                "INBOUND_CONFIRMATION_MESSAGE", null,
                "DENIED", "SIGNATURE_OR_REPLAY_WINDOW_INVALID",
                Map.of(
                    "fingerprintHash",
                    hasher.sha256(input.providerMessageFingerprint())));
            throw new jakarta.persistence.EntityNotFoundException(
                "Resource not found.");
        }
        String requestHash = hasher.hash(Map.of(
            "schema", "f04-inbound-record-v1",
            "monthId", monthId,
            "request", input)).checksum();
        UUID prior = priorResultId(
            subject, "RECORD_INBOUND_MESSAGE", monthId,
            idempotencyKey, requestHash);
        if (prior != null) {
            return inboundView(
                oneInbound(prior, false), reviewSlaSeconds(monthId));
        }
        requireCurrentMonthVersion(monthId, expected);

        boolean authenticated =
            "PASS".equals(input.authentication().spf())
                && "PASS".equals(input.authentication().dkim())
                && "PASS".equals(input.authentication().dmarc());
        boolean explicit = Set.of(
            "EXPLICIT_CONFIRM", "EXPLICIT_CORRECTION", "EXPLICIT_REJECT")
            .contains(input.classifiedIntent());
        String status = authenticated && explicit && input.requestId() != null
            ? "MANUAL_REVIEW_REQUIRED" : "QUARANTINED";
        UUID messageId = UUID.randomUUID();
        UUID correlationId = CorrelationIdFilter.currentOrNew();
        jdbc.update("""
            INSERT INTO inbound_confirmation_messages
                (id, engagement_month_id, request_id,
                 provider_message_fingerprint, provider_message_id,
                 provider_thread_id, sender_address_hash,
                 raw_reference, raw_sha256, in_reply_to_hash,
                 references_hash, authentication_evidence,
                 classified_intent, status, provider_received_at,
                 correlation_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb,
                    ?, ?, ?, ?)
            """, messageId, monthId, input.requestId(),
            input.providerMessageFingerprint(), input.providerMessageId(),
            input.providerThreadId(),
            hasher.sha256(
                input.senderAddress().strip().toLowerCase(Locale.ROOT)),
            input.rawReference(), input.rawSha256(),
            input.inReplyToHash(), input.referencesHash(),
            hasher.hash(Map.of(
                "spf", input.authentication().spf().toLowerCase(Locale.ROOT),
                "dkim", input.authentication().dkim().toLowerCase(Locale.ROOT),
                "dmarc", input.authentication().dmarc().toLowerCase(Locale.ROOT),
                "verified", authenticated,
                "callbackSignatureVerified", true)).canonicalJson(),
            input.classifiedIntent(), status,
            input.providerReceivedAt(), correlationId);
        if ("QUARANTINED".equals(status)) {
            securityEvents.recordBestEffort(
                monthId, "INBOUND_MESSAGE_QUARANTINED", subject,
                "INBOUND_CONFIRMATION_MESSAGE", messageId,
                "QUARANTINED", "AUTHENTICATION_OR_INTENT_NOT_APPROVABLE",
                Map.of("classifiedIntent", input.classifiedIntent()));
        }
        UUID policyId = policyId(monthId, input.requestId());
        audit(
            monthId, "INBOUND_CONFIRMATION_RECORDED", subject,
            "inbound_confirmation_message", messageId,
            "Provider signature, replay window and fingerprint verified",
            status, policyId, correlationId,
            CertificationAuthorizationService.INBOUND_INGEST,
            "INTEGRATION");
        event(
            monthId, "confirmation.inbound.recorded.v1", subject,
            "inbound_confirmation_message", messageId, correlationId,
            Map.of(
                "intent", input.classifiedIntent(),
                "status", status,
                "authenticationVerified", authenticated),
            "INTEGRATION");
        recordIdempotency(
            subject, "RECORD_INBOUND_MESSAGE", monthId, idempotencyKey,
            requestHash, "inbound_confirmation_message", messageId);
        bumpMonth(monthId);
        return inboundView(
            oneInbound(messageId, false), reviewSlaSeconds(monthId));
    }

    @Transactional
    public InboundReviewView recordManualEvidence(
        String subject,
        UUID monthId,
        ManualEvidenceRecordInput input,
        String ifMatch,
        String idempotencyKey
    ) {
        authorization.requireInboundReview(subject, monthId);
        requireIdempotencyKey(idempotencyKey);
        long expected = requireMatchingMonthVersion(
            ifMatch, input.expectedMonthVersion());
        String requestHash = hasher.hash(Map.of(
            "schema", "f04-manual-evidence-record-v1",
            "monthId", monthId,
            "request", input)).checksum();
        UUID prior = priorResultId(
            subject, "RECORD_MANUAL_EVIDENCE", monthId,
            idempotencyKey, requestHash);
        if (prior != null) {
            return manualView(
                oneManual(prior, false), reviewSlaSeconds(monthId));
        }
        requireCurrentMonthVersion(monthId, expected);
        UUID evidenceId = UUID.randomUUID();
        UUID correlationId = CorrelationIdFilter.currentOrNew();
        jdbc.update("""
            INSERT INTO manual_confirmation_evidence
                (id, engagement_month_id, request_id, artifact_id,
                 evidence_format, sender_address, recipients,
                 subject_text, message_id, sent_or_received_at,
                 represented_decision, file_hash, recorded_by_subject)
            VALUES (?, ?, ?, ?, ?, ?, to_jsonb(?::text[]), ?, ?, ?, ?, ?, ?)
            """, evidenceId, monthId, input.requestId(), input.artifactId(),
            input.evidenceFormat(), input.senderAddress().strip(),
            input.recipients().toArray(String[]::new), input.subject(),
            input.messageId(), input.sentOrReceivedAt(),
            input.representedDecision(), input.fileHash(), subject);
        UUID policyId = policyId(monthId, input.requestId());
        audit(
            monthId, "MANUAL_CONFIRMATION_EVIDENCE_RECORDED", subject,
            "manual_confirmation_evidence", evidenceId,
            "Restricted metadata recorded for distinct second review",
            "PENDING_SECOND_REVIEW", policyId, correlationId);
        event(
            monthId, "confirmation.manual-evidence.recorded.v1", subject,
            "manual_confirmation_evidence", evidenceId, correlationId,
            Map.of(
                "format", input.evidenceFormat(),
                "representedDecision", input.representedDecision(),
                "artifactId", input.artifactId()));
        recordIdempotency(
            subject, "RECORD_MANUAL_EVIDENCE", monthId, idempotencyKey,
            requestHash, "manual_confirmation_evidence", evidenceId);
        bumpMonth(monthId);
        return manualView(
            oneManual(evidenceId, false), reviewSlaSeconds(monthId));
    }

    @Transactional
    public InboundReviewView reviewInbound(
        String subject,
        UUID messageId,
        InboundMessageReviewInput input,
        String ifMatch,
        String idempotencyKey
    ) {
        requireIdempotencyKey(idempotencyKey);
        InboundRow initial = oneInbound(messageId, false);
        authorization.requireInboundReview(subject, initial.monthId());
        requireMatchingVersion(ifMatch, input.expectedReviewVersion());
        String requestHash = hasher.hash(Map.of(
            "expectedReviewVersion", input.expectedReviewVersion(),
            "decision", input.decision(),
            "reasoning", input.reasoning())).checksum();
        if (priorResult(
                subject, "REVIEW_INBOUND_MESSAGE", messageId,
                idempotencyKey, requestHash)) {
            return inboundView(oneInbound(messageId, false),
                reviewSlaSeconds(initial.monthId()));
        }

        InboundRow row = oneInbound(messageId, true);
        requirePending(row.reviewDecision(), input.expectedReviewVersion());
        UUID reviewId = UUID.randomUUID();
        UUID correlationId = CorrelationIdFilter.currentOrNew();
        jdbc.update("""
            INSERT INTO inbound_confirmation_reviews
                (id, inbound_message_id, reviewer_subject, decision, reasoning)
            VALUES (?, ?, ?, ?, ?)
            """, reviewId, messageId, subject, input.decision(), input.reasoning());
        UUID auditId = audit(
            row.monthId(), "INBOUND_CONFIRMATION_REVIEWED", subject,
            "inbound_confirmation_message", messageId, input.reasoning(),
            input.decision(), row.policyId(), correlationId);
        boolean promotableReply =
            "ACCEPT_INTERPRETATION".equals(input.decision())
                && Set.of(
                    "EXPLICIT_CONFIRM", "EXPLICIT_CORRECTION",
                    "EXPLICIT_REJECT").contains(row.classifiedIntent())
                && "VERIFIED".equals(authenticationConfidence(
                    row.authenticationEvidence()))
                && "ELIGIBLE".equals(senderEligibility(
                    row.requestId(), row.senderAddressHash()));
        UUID actionId = promotableReply
            ? confirmations.promoteReviewedEvidence(
                subject, "INBOUND_MESSAGE", messageId, reviewId)
            : null;
        event(
            row.monthId(), "confirmation.inbound.reviewed.v1", subject,
            "inbound_confirmation_message", messageId, correlationId,
            actionId == null
                ? Map.of(
                    "decision", input.decision(),
                    "auditReference", auditId)
                : Map.of(
                    "decision", input.decision(),
                    "auditReference", auditId,
                    "confirmationActionId", actionId));
        recordIdempotency(
            subject, "REVIEW_INBOUND_MESSAGE", messageId, idempotencyKey,
            requestHash, "inbound_confirmation_message", messageId);
        return inboundView(oneInbound(messageId, false),
            reviewSlaSeconds(row.monthId()));
    }

    @Transactional
    public InboundReviewView reviewManualEvidence(
        String subject,
        UUID evidenceId,
        ManualEvidenceReviewInput input,
        String ifMatch,
        String idempotencyKey
    ) {
        requireIdempotencyKey(idempotencyKey);
        ManualRow initial = oneManual(evidenceId, false);
        authorization.requireInboundReview(subject, initial.monthId());
        requireMatchingVersion(ifMatch, input.expectedReviewVersion());
        String requestHash = hasher.hash(Map.of(
            "expectedReviewVersion", input.expectedReviewVersion(),
            "decision", input.decision(),
            "reasoning", input.reasoning())).checksum();
        if (priorResult(
                subject, "REVIEW_MANUAL_EVIDENCE", evidenceId,
                idempotencyKey, requestHash)) {
            return manualView(oneManual(evidenceId, false),
                reviewSlaSeconds(initial.monthId()));
        }

        ManualRow row = oneManual(evidenceId, true);
        requirePending(row.reviewDecision(), input.expectedReviewVersion());
        if (subject.equals(row.recordedBySubject())) {
            throw new DomainConflictException(
                "SECOND_REVIEWER_REQUIRED",
                "Manual evidence requires a distinct authorized second reviewer.");
        }
        UUID reviewId = UUID.randomUUID();
        UUID correlationId = CorrelationIdFilter.currentOrNew();
        jdbc.update("""
            INSERT INTO manual_confirmation_evidence_reviews
                (id, manual_evidence_id, reviewer_subject, decision, reasoning,
                 authority_snapshot)
            VALUES (?, ?, ?, ?, ?, ?::jsonb)
            """, reviewId, evidenceId, subject, input.decision(), input.reasoning(),
            "{\"permission\":\"certification.inbound.review\","
                + "\"resolvedServerSide\":true}");
        UUID auditId = audit(
            row.monthId(), "MANUAL_CONFIRMATION_EVIDENCE_REVIEWED", subject,
            "manual_confirmation_evidence", evidenceId, input.reasoning(),
            input.decision(), row.policyId(), correlationId);
        UUID actionId = "APPROVE".equals(input.decision())
            ? confirmations.promoteReviewedEvidence(
                subject, "MANUAL_EVIDENCE", evidenceId, reviewId)
            : null;
        event(
            row.monthId(), "confirmation.manual-evidence.reviewed.v1", subject,
            "manual_confirmation_evidence", evidenceId, correlationId,
            actionId == null
                ? Map.of(
                    "decision", input.decision(),
                    "auditReference", auditId)
                : Map.of(
                    "decision", input.decision(),
                    "auditReference", auditId,
                    "confirmationActionId", actionId));
        recordIdempotency(
            subject, "REVIEW_MANUAL_EVIDENCE", evidenceId, idempotencyKey,
            requestHash, "manual_confirmation_evidence", evidenceId);
        return manualView(oneManual(evidenceId, false),
            reviewSlaSeconds(row.monthId()));
    }

    private InboundReviewView inboundView(InboundRow row, int reviewSlaSeconds) {
        String authentication = authenticationConfidence(row.authenticationEvidence());
        String source = switch (row.status()) {
            case "QUARANTINED" -> "QUARANTINED";
            default -> switch (row.classifiedIntent()) {
                case "AMBIGUOUS" -> "AMBIGUOUS_REPLY";
                case "EXPLICIT_CONFIRM", "EXPLICIT_CORRECTION", "EXPLICIT_REJECT" ->
                    "VERIFIED".equals(authentication)
                        ? "VERIFIED_REPLY" : "AMBIGUOUS_REPLY";
                default -> "QUARANTINED";
            };
        };
        String reviewStatus = inboundReviewStatus(row.reviewDecision());
        String senderEligibility = senderEligibility(
            row.requestId(), row.senderAddressHash());
        return new InboundReviewView(
            row.id(), "INBOUND_MESSAGE", source, authentication, reviewStatus,
            senderEligibility, row.reviewDecision() == null ? 0 : 1,
            true, "Authorized restricted client/Procurement reviewer",
            row.representedAt(), row.recordedAt(),
            ageSeconds(row.recordedAt()),
            agingStatus(row.recordedAt(), reviewStatus, reviewSlaSeconds),
            safeInboundSummary(row.classifiedIntent(), source),
            row.reviewReasoning(), row.auditReference());
    }

    private InboundReviewView manualView(ManualRow row, int reviewSlaSeconds) {
        String reviewStatus = switch (row.reviewDecision() == null
            ? row.verificationStatus() : row.reviewDecision()) {
            case "APPROVED", "APPROVE" -> "APPROVED";
            case "REJECTED", "REJECT" -> "REJECTED";
            default -> "PENDING";
        };
        return new InboundReviewView(
            row.id(), "MANUAL_EVIDENCE", "MANUAL_EVIDENCE", "UNAVAILABLE",
            reviewStatus, senderEligibility(row.requestId(), row.senderAddressHash()),
            row.reviewDecision() == null ? 0 : 1,
            true, "Authorized distinct client/Procurement second reviewer",
            row.representedAt(), row.recordedAt(),
            ageSeconds(row.recordedAt()),
            agingStatus(row.recordedAt(), reviewStatus, reviewSlaSeconds),
            "Manual " + row.evidenceFormat()
                + " evidence represents a " + row.representedDecision()
                + " decision and requires a distinct review.",
            row.reviewReasoning(), row.auditReference());
    }

    private List<InboundRow> inboundRows(
        UUID monthId,
        UUID messageId,
        boolean lock
    ) {
        String predicate = messageId == null
            ? "message.engagement_month_id = ?"
            : "message.id = ?";
        String sql = """
            SELECT message.id, message.engagement_month_id, message.request_id,
                   message.sender_address_hash,
                   message.authentication_evidence::text AS authentication_evidence,
                   message.classified_intent, message.status,
                   message.provider_received_at, message.recorded_at,
                   review.decision AS review_decision,
                   review.reasoning AS review_reasoning,
                   request.policy_version_id,
                   audit.id AS audit_reference
            FROM inbound_confirmation_messages message
            LEFT JOIN business_confirmation_requests request
              ON request.id = message.request_id
            LEFT JOIN inbound_confirmation_reviews review
              ON review.inbound_message_id = message.id
            LEFT JOIN LATERAL (
                SELECT event.id
                FROM certification_audit_events event
                WHERE event.object_type = 'inbound_confirmation_message'
                  AND event.object_id = message.id
                  AND event.event_type = 'INBOUND_CONFIRMATION_REVIEWED'
                ORDER BY event.occurred_at DESC
                LIMIT 1
            ) audit ON TRUE
            WHERE %s
              AND message.engagement_month_id IS NOT NULL
            ORDER BY message.recorded_at DESC
            %s
            """.formatted(
                predicate, lock ? "FOR UPDATE OF message" : "");
        return jdbc.query(sql,
            (rs, rowNum) -> new InboundRow(
                rs.getObject("id", UUID.class),
                rs.getObject("engagement_month_id", UUID.class),
                rs.getObject("request_id", UUID.class),
                rs.getString("sender_address_hash"),
                rs.getString("authentication_evidence"),
                rs.getString("classified_intent"),
                rs.getString("status"),
                rs.getObject("provider_received_at", OffsetDateTime.class),
                rs.getObject("recorded_at", OffsetDateTime.class),
                rs.getString("review_decision"),
                rs.getString("review_reasoning"),
                rs.getObject("policy_version_id", UUID.class),
                rs.getObject("audit_reference", UUID.class)),
            messageId == null ? monthId : messageId);
    }

    private List<ManualRow> manualRows(
        UUID monthId,
        UUID evidenceId,
        boolean lock
    ) {
        String predicate = evidenceId == null
            ? "evidence.engagement_month_id = ?"
            : "evidence.id = ?";
        String sql = """
            SELECT evidence.id, evidence.engagement_month_id, evidence.request_id,
                   evidence.evidence_format, evidence.sender_address,
                   evidence.represented_decision, evidence.verification_status,
                   evidence.sent_or_received_at, evidence.recorded_at,
                   evidence.recorded_by_subject,
                   review.decision AS review_decision,
                   review.reasoning AS review_reasoning,
                   request.policy_version_id,
                   audit.id AS audit_reference
            FROM manual_confirmation_evidence evidence
            LEFT JOIN business_confirmation_requests request
              ON request.id = evidence.request_id
            LEFT JOIN manual_confirmation_evidence_reviews review
              ON review.manual_evidence_id = evidence.id
            LEFT JOIN LATERAL (
                SELECT event.id
                FROM certification_audit_events event
                WHERE event.object_type = 'manual_confirmation_evidence'
                  AND event.object_id = evidence.id
                  AND event.event_type =
                      'MANUAL_CONFIRMATION_EVIDENCE_REVIEWED'
                ORDER BY event.occurred_at DESC
                LIMIT 1
            ) audit ON TRUE
            WHERE %s
            ORDER BY evidence.recorded_at DESC
            %s
            """.formatted(
                predicate, lock ? "FOR UPDATE OF evidence" : "");
        return jdbc.query(sql,
            (rs, rowNum) -> new ManualRow(
                rs.getObject("id", UUID.class),
                rs.getObject("engagement_month_id", UUID.class),
                rs.getObject("request_id", UUID.class),
                rs.getString("evidence_format"),
                hasher.sha256(rs.getString("sender_address")
                    .strip().toLowerCase(Locale.ROOT)),
                rs.getString("represented_decision"),
                rs.getString("verification_status"),
                rs.getObject("sent_or_received_at", OffsetDateTime.class),
                rs.getObject("recorded_at", OffsetDateTime.class),
                rs.getString("recorded_by_subject"),
                rs.getString("review_decision"),
                rs.getString("review_reasoning"),
                rs.getObject("policy_version_id", UUID.class),
                rs.getObject("audit_reference", UUID.class)),
            evidenceId == null ? monthId : evidenceId);
    }

    private InboundRow oneInbound(UUID id, boolean lock) {
        List<InboundRow> rows = inboundRows(null, id, lock);
        if (rows.size() != 1) {
            throw new jakarta.persistence.EntityNotFoundException(
                "Resource not found.");
        }
        return rows.getFirst();
    }

    private ManualRow oneManual(UUID id, boolean lock) {
        List<ManualRow> rows = manualRows(null, id, lock);
        if (rows.size() != 1) {
            throw new jakarta.persistence.EntityNotFoundException(
                "Resource not found.");
        }
        return rows.getFirst();
    }

    private String senderEligibility(UUID requestId, String addressHash) {
        if (requestId == null || addressHash == null) {
            return "UNKNOWN";
        }
        List<String> eligibleAddresses = jdbc.query("""
            SELECT snapshot.verified_email
            FROM confirmation_request_eligibility eligibility
            JOIN confirmation_eligibility_snapshots snapshot
              ON snapshot.id = eligibility.eligibility_id
            WHERE eligibility.request_id = ?
            """, (rs, rowNum) -> rs.getString(1), requestId);
        return eligibleAddresses.stream()
            .map(value -> hasher.sha256(value.strip().toLowerCase(Locale.ROOT)))
            .anyMatch(addressHash::equalsIgnoreCase)
            ? "ELIGIBLE" : "INELIGIBLE";
    }

    private String authenticationConfidence(String json) {
        String normalized = json == null
            ? "" : json.toLowerCase(Locale.ROOT).replace(" ", "");
        if (normalized.isBlank() || "{}".equals(normalized)) {
            return "UNAVAILABLE";
        }
        if (normalized.contains("\"verified\":true")
            || (normalized.contains("\"spf\":\"pass\"")
                && normalized.contains("\"dkim\":\"pass\"")
                && normalized.contains("\"dmarc\":\"pass\""))) {
            return "VERIFIED";
        }
        if (normalized.contains("\"verified\":false")
            || normalized.contains("\"spf\":\"fail\"")
            || normalized.contains("\"dkim\":\"fail\"")
            || normalized.contains("\"dmarc\":\"fail\"")) {
            return "FAILED";
        }
        return "PARTIAL";
    }

    private String inboundReviewStatus(String decision) {
        if (decision == null) {
            return "PENDING";
        }
        return switch (decision) {
            case "ACCEPT_INTERPRETATION" -> "APPROVED";
            case "REJECT_INTERPRETATION" -> "REJECTED";
            case "QUARANTINE" -> "QUARANTINED";
            default -> "PENDING";
        };
    }

    private String safeInboundSummary(String intent, String source) {
        if ("QUARANTINED".equals(source)) {
            return "Non-confirming inbound metadata is quarantined for restricted review.";
        }
        if ("AMBIGUOUS".equals(intent)) {
            return "Ambiguous inbound intent requires an authorized human decision.";
        }
        return "Explicit classified intent requires restricted authentication and eligibility review.";
    }

    private long ageSeconds(OffsetDateTime recordedAt) {
        if (recordedAt == null) {
            return 0;
        }
        return Math.max(0, Duration.between(
            recordedAt.toInstant(), clock.instant()).toSeconds());
    }

    private String agingStatus(
        OffsetDateTime recordedAt,
        String reviewStatus,
        int reviewSlaSeconds
    ) {
        if (!"PENDING".equals(reviewStatus)) {
            return "RESOLVED";
        }
        long age = ageSeconds(recordedAt);
        if (age >= (long) reviewSlaSeconds * 2) {
            return "OVERDUE";
        }
        return age >= reviewSlaSeconds ? "AGING" : "NEW";
    }

    private int reviewSlaSeconds(UUID monthId) {
        Integer value = jdbc.query("""
            SELECT COALESCE(
                (policy.reminder_policy ->> 'reviewSlaSeconds')::integer,
                ?)
            FROM engagement_months month
            LEFT JOIN certification_policy_versions policy
              ON policy.engagement_id = month.engagement_id
             AND policy.status = 'ACTIVE'
            WHERE month.id = ?
            """, rs -> rs.next() ? rs.getInt(1) : null,
            DEFAULT_REVIEW_SLA_SECONDS, monthId);
        return value == null || value <= 0
            ? DEFAULT_REVIEW_SLA_SECONDS : value;
    }

    private void requirePending(String existingDecision, int expectedVersion) {
        int currentVersion = existingDecision == null ? 0 : 1;
        if (expectedVersion != currentVersion) {
            throw new DomainConflictException(
                "REVIEW_VERSION_CONFLICT",
                "A newer restricted review decision exists.",
                (long) currentVersion);
        }
        if (existingDecision != null) {
            throw new DomainConflictException(
                "REVIEW_ALREADY_DECIDED",
                "The restricted review item already has an append-only decision.",
                1L);
        }
    }

    private void requireMatchingVersion(String ifMatch, int bodyVersion) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new IllegalArgumentException("If-Match is required.");
        }
        String normalized = ifMatch.trim();
        if (normalized.startsWith("W/")) {
            normalized = normalized.substring(2);
        }
        normalized = normalized.replace("\"", "");
        int headerVersion;
        try {
            headerVersion = Integer.parseInt(normalized);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                "If-Match must contain a numeric review version.");
        }
        if (headerVersion != bodyVersion) {
            throw new IllegalArgumentException(
                "If-Match and expectedReviewVersion must match.");
        }
    }

    private void requireIdempotencyKey(String value) {
        if (value == null || value.isBlank() || value.length() > 160) {
            throw new IllegalArgumentException(
                "Idempotency-Key is required and must not exceed 160 characters.");
        }
    }

    private boolean priorResult(
        String actor,
        String operation,
        UUID scopeId,
        String key,
        String requestHash
    ) {
        return priorResultId(
            actor, operation, scopeId, key, requestHash) != null;
    }

    private UUID priorResultId(
        String actor,
        String operation,
        UUID scopeId,
        String key,
        String requestHash
    ) {
        IdempotencyRow prior = jdbc.query("""
            SELECT request_hash, result_id
            FROM certification_idempotency_keys
            WHERE actor_subject = ? AND operation = ?
              AND scope_id = ? AND idempotency_key = ?
            """, rs -> rs.next()
                ? new IdempotencyRow(rs.getString(1), rs.getObject(2, UUID.class))
                : null, actor, operation, scopeId, key);
        if (prior == null) {
            return null;
        }
        if (!prior.requestHash().equals(requestHash)) {
            throw new DomainConflictException(
                "IDEMPOTENCY_KEY_REUSED",
                "The idempotency key was already used with different input.");
        }
        return prior.resultId();
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

    private UUID audit(
        UUID monthId,
        String eventType,
        String actor,
        String objectType,
        UUID objectId,
        String reason,
        String result,
        UUID policyId,
        UUID correlationId
    ) {
        return audit(
            monthId, eventType, actor, objectType, objectId,
            reason, result, policyId, correlationId,
            CertificationAuthorizationService.INBOUND_REVIEW, "IN_APP");
    }

    private UUID audit(
        UUID monthId,
        String eventType,
        String actor,
        String objectType,
        UUID objectId,
        String reason,
        String result,
        UUID policyId,
        UUID correlationId,
        String permission,
        String source
    ) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO certification_audit_events
                (id, engagement_month_id, event_type, actor_subject,
                 authority_snapshot, object_type, object_id, object_version,
                 source, reason, result, correlation_id, policy_version_id)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, 1, ?, ?, ?, ?, ?)
            """, id, monthId, eventType, actor,
            hasher.hash(Map.of(
                "permission", permission,
                "resolvedServerSide", true)).canonicalJson(),
            objectType, objectId, source, reason, result,
            correlationId, policyId);
        return id;
    }

    private void event(
        UUID monthId,
        String eventType,
        String actor,
        String subjectType,
        UUID subjectId,
        UUID correlationId,
        Map<String, ?> payload
    ) {
        event(
            monthId, eventType, actor, subjectType, subjectId,
            correlationId, payload, "USER");
    }

    private void event(
        UUID monthId,
        String eventType,
        String actor,
        String subjectType,
        UUID subjectId,
        UUID correlationId,
        Map<String, ?> payload,
        String actorType
    ) {
        String safePayload = hasher.hash(payload).canonicalJson();
        jdbc.update("""
            INSERT INTO certification_domain_events
                (id, engagement_month_id, event_type, actor_type, actor_subject,
                 subject_type, subject_id, subject_version, correlation_id, payload)
            VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?::jsonb)
            """, UUID.randomUUID(), monthId, eventType, actorType, actor,
            subjectType, subjectId, correlationId, safePayload);
    }

    private long requireMatchingMonthVersion(
        String ifMatch,
        long bodyVersion
    ) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new IllegalArgumentException("If-Match is required.");
        }
        String normalized = ifMatch.strip();
        if (normalized.startsWith("W/")) {
            normalized = normalized.substring(2);
        }
        if (normalized.length() >= 2
            && normalized.startsWith("\"")
            && normalized.endsWith("\"")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        long headerVersion;
        try {
            headerVersion = Long.parseLong(normalized);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                "If-Match must contain a numeric month version.");
        }
        if (headerVersion != bodyVersion) {
            throw new IllegalArgumentException(
                "If-Match and expectedMonthVersion must match.");
        }
        return headerVersion;
    }

    private void requireCurrentMonthVersion(UUID monthId, long expected) {
        Long current = jdbc.query("""
            SELECT certification_version
            FROM engagement_months
            WHERE id = ?
            FOR UPDATE
            """, rs -> rs.next() ? rs.getLong(1) : null, monthId);
        if (current == null) {
            throw new jakarta.persistence.EntityNotFoundException(
                "Resource not found.");
        }
        if (current != expected) {
            throw new DomainConflictException(
                "MONTH_VERSION_CONFLICT",
                "The engagement month version is stale.", current);
        }
    }

    private UUID policyId(UUID monthId, UUID requestId) {
        return jdbc.query("""
            SELECT COALESCE(
                (
                    SELECT request.policy_version_id
                    FROM business_confirmation_requests request
                    WHERE request.id = ?
                      AND request.engagement_month_id = month.id
                ),
                (
                    SELECT policy.id
                    FROM certification_policy_versions policy
                    WHERE policy.engagement_id = month.engagement_id
                      AND policy.status = 'ACTIVE'
                )
            )
            FROM engagement_months month
            WHERE month.id = ?
            """, rs -> rs.next()
                ? rs.getObject(1, UUID.class) : null,
            requestId, monthId);
    }

    private void bumpMonth(UUID monthId) {
        jdbc.update("""
            UPDATE engagement_months
            SET certification_version = certification_version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """, monthId);
    }

    private record InboundRow(
        UUID id,
        UUID monthId,
        UUID requestId,
        String senderAddressHash,
        String authenticationEvidence,
        String classifiedIntent,
        String status,
        OffsetDateTime representedAt,
        OffsetDateTime recordedAt,
        String reviewDecision,
        String reviewReasoning,
        UUID policyId,
        UUID auditReference
    ) {
    }

    private record ManualRow(
        UUID id,
        UUID monthId,
        UUID requestId,
        String evidenceFormat,
        String senderAddressHash,
        String representedDecision,
        String verificationStatus,
        OffsetDateTime representedAt,
        OffsetDateTime recordedAt,
        String recordedBySubject,
        String reviewDecision,
        String reviewReasoning,
        UUID policyId,
        UUID auditReference
    ) {
    }

    private record IdempotencyRow(String requestHash, UUID resultId) {
    }
}
