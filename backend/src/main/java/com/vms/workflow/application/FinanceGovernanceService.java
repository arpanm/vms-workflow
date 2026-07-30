package com.vms.workflow.application;

import com.vms.workflow.api.FinanceController.CreateExportInput;
import com.vms.workflow.api.FinanceController.PaymentUpdateInput;
import com.vms.workflow.api.FinanceController.ProcurementExceptionApprovalInput;
import com.vms.workflow.api.FinanceController.ProcurementExceptionInput;
import com.vms.workflow.api.FinanceController.ProcurementQueryInput;
import com.vms.workflow.api.FinanceController.ProcurementReviewInput;
import com.vms.workflow.api.DomainConflictException;
import com.vms.workflow.security.FinanceAuthorizationService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Scoped Procurement, AP and reporting workflows. Upstream delivery facts are
 * queried only through F05 projections and never mutated here.
 */
@Service
public class FinanceGovernanceService {
    private final JdbcTemplate jdbc;
    private final FinanceAuthorizationService authorization;
    private final FinanceMutationJournal journal;
    private final FinanceCanonicalJson canonical;
    private final FinancePrivateStorageAdapter storage;
    private final FinancePolicyService policies;
    private final FinanceExceptionValidityService exceptionValidity;
    private final FinancePageCursorCodec cursors;
    private final FinanceAccessDenialRecorder denialRecorder;
    private final Clock clock;

    public FinanceGovernanceService(
        JdbcTemplate jdbc,
        FinanceAuthorizationService authorization,
        FinanceMutationJournal journal,
        FinanceCanonicalJson canonical,
        FinancePrivateStorageAdapter storage,
        FinancePolicyService policies,
        FinanceExceptionValidityService exceptionValidity,
        FinancePageCursorCodec cursors,
        FinanceAccessDenialRecorder denialRecorder,
        Clock clock
    ) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.journal = journal;
        this.canonical = canonical;
        this.storage = storage;
        this.policies = policies;
        this.exceptionValidity = exceptionValidity;
        this.cursors = cursors;
        this.denialRecorder = denialRecorder;
        this.clock = clock;
    }

    public Map<String, Object> controlTower(String subject) {
        return controlTower(subject, null);
    }

    /**
     * Pages over a membership snapshot of month IDs. The mutable tower values
     * are intentionally read live on each request; this is not a historical
     * value snapshot.
     */
    public Map<String, Object> controlTower(String subject, String cursor) {
        List<UUID> engagements = authorizedEngagements(
            subject, "finance.read");
        if (engagements.isEmpty()) {
            throw new EntityNotFoundException("Finance scope not found.");
        }
        FinancePageCursorCodec.Cursor decoded =
            decodeCursor(cursor, "control-tower", subject, engagements);
        Instant snapshotAt = decoded == null
            ? clock.instant() : decoded.snapshotAt();
        LocalDate lastDate = decoded == null ? null
            : LocalDate.parse(decoded.lastSortValue());
        UUID lastId = decoded == null ? null : decoded.lastId();
        List<Map<String, Object>> rows = jdbc.query("""
            SELECT tower.engagement_month_id, tower.month_start_date,
                   engagement.name,
                   tower.package_version_id, tower.package_version,
                   tower.package_status, tower.invoice_id,
                   tower.invoice_number, tower.invoice_status,
                   tower.readiness_status, tower.readiness_evaluated_at,
                   tower.payment_status, tower.payment_status_at,
                   tower.reopened_or_invalidated
            FROM f05_control_tower tower
            JOIN engagements engagement ON engagement.id = tower.engagement_id
            JOIN engagement_months month
              ON month.id = tower.engagement_month_id
            WHERE tower.engagement_id = ANY (?::uuid[])
              AND month.created_at <= ?
              AND (
                  ?::date IS NULL
                  OR (tower.month_start_date, tower.engagement_month_id)
                     < (?::date, ?::uuid)
              )
            ORDER BY tower.month_start_date DESC,
                     tower.engagement_month_id DESC
            LIMIT 51
            """, (rs, index) -> controlTowerRow(
                rs.getObject(1, UUID.class),
                rs.getDate(2).toLocalDate(),
                rs.getString(3),
                rs.getObject(4, UUID.class),
                (Integer) rs.getObject(5),
                rs.getString(6),
                rs.getObject(7, UUID.class),
                rs.getString(8),
                rs.getString(9),
                rs.getString(10),
                offset(rs.getTimestamp(11)),
                rs.getString(12),
                offset(rs.getTimestamp(13)),
                rs.getBoolean(14)),
            engagements.toArray(UUID[]::new),
            Timestamp.from(snapshotAt),
            lastDate, lastDate, lastId);
        long total = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM f05_control_tower tower
            JOIN engagement_months month
              ON month.id = tower.engagement_month_id
            WHERE tower.engagement_id = ANY (?::uuid[])
              AND month.created_at <= ?
            """, Long.class, engagements.toArray(UUID[]::new),
            Timestamp.from(snapshotAt));
        return map(
            "permissions", governancePermissions(subject, engagements),
            "refreshedAt", OffsetDateTime.now(clock),
            "freshness", "LIVE_AT_READ",
            "membershipSnapshotAt",
                OffsetDateTime.ofInstant(snapshotAt, ZoneOffset.UTC),
            "temporalMode", "SNAPSHOT_MEMBERSHIP_VALUES_LIVE_AT_READ",
            "rows", cursorPage(
                rows, total, "control-tower", subject, engagements,
                snapshotAt, "monthLabel", "monthId"));
    }

    @Transactional(noRollbackFor = DomainConflictException.class)
    public Map<String, Object> review(
        String subject,
        UUID invoiceId,
        String ifMatch,
        String idempotencyKey,
        ProcurementReviewInput request
    ) {
        var scope = authorization.requireInvoice(
            subject, invoiceId, "procurement.review",
            FinanceAuthorizationService.Party.PROCUREMENT);
        exceptionValidity.expireInvoice(invoiceId, subject);
        requireIfMatch(ifMatch, request.expectedVersion());
        UUID replay = journal.replay(
            subject, "PROCUREMENT_REVIEW", invoiceId, idempotencyKey, request);
        if (replay != null) {
            return invoiceMutationView(invoiceId);
        }
        InvoiceRow invoice = lockInvoice(invoiceId, request.expectedVersion());
        requireSeparation(subject, invoice);
        Set<String> decisions = Set.of(
            "APPROVED_FOR_PROCESSING", "CHANGES_REQUESTED",
            "ON_HOLD", "REJECTED");
        if (!decisions.contains(request.decision())) {
            throw new IllegalArgumentException("Unsupported Procurement decision.");
        }
        if (!"APPROVED_FOR_PROCESSING".equals(request.decision())
            && (blank(request.category()) || blank(request.comment()))) {
            throw new IllegalArgumentException(
                "A category and comment are required for a non-approval.");
        }
        requireReviewState(invoice.status());
        requireExactInputs(invoice, request.packageId(),
            request.packageVersion(), request.readinessRunId(),
            "APPROVED_FOR_PROCESSING".equals(request.decision()));

        UUID reviewId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO procurement_reviews(
                id, invoice_id, invoice_version, package_version_id,
                readiness_run_id, decision, category, comment,
                authority_snapshot, reviewed_by_subject, correlation_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            """, reviewId, invoiceId, invoice.currentVersion(),
            request.packageId(), request.readinessRunId(), request.decision(),
            clean(request.category(), 80), clean(request.comment(), 1000),
            canonical.write(authority(scope, "procurement.review")),
            subject, journal.correlationId());
        long newVersion = transitionInvoice(
            invoice, request.decision(), request.expectedVersion());
        journal.event(invoice.monthId(), "f05.procurement.reviewed.v1",
            "INVOICE", invoiceId, newVersion, map(
                "reviewId", reviewId,
                "decision", request.decision(),
                "packageId", request.packageId(),
                "readinessRunId", request.readinessRunId()), subject);
        journal.audit(invoice.monthId(), "PROCUREMENT_REVIEWED", "INVOICE",
            invoiceId, newVersion, "SUCCESS", request.category(), subject,
            authority(scope, "procurement.review"),
            List.of(reference("PROCUREMENT_REVIEW", reviewId)));
        journal.remember(subject, "PROCUREMENT_REVIEW", invoiceId,
            idempotencyKey, request, "PROCUREMENT_REVIEW", reviewId);
        return invoiceMutationView(invoiceId);
    }

    @Transactional
    public Map<String, Object> createQuery(
        String subject,
        UUID invoiceId,
        String ifMatch,
        String idempotencyKey,
        ProcurementQueryInput request
    ) {
        var scope = authorization.requireInvoice(
            subject, invoiceId, "procurement.review",
            FinanceAuthorizationService.Party.PROCUREMENT);
        requireIfMatch(ifMatch, request.expectedVersion());
        UUID replay = journal.replay(
            subject, "PROCUREMENT_QUERY", invoiceId, idempotencyKey, request);
        if (replay != null) {
            return invoiceMutationView(invoiceId);
        }
        InvoiceRow invoice = lockInvoice(invoiceId, request.expectedVersion());
        requireSeparation(subject, invoice);
        requireReviewState(invoice.status());
        if (!request.dueAt().isAfter(OffsetDateTime.now(clock))) {
            throw new IllegalArgumentException("Query dueAt must be in the future.");
        }
        if (!activeScopedOwner(request.ownerId(), invoice.engagementId())) {
            throw new IllegalArgumentException(
                "Query owner is not an active identity in this engagement scope.");
        }
        InputSet current = currentInputs(invoiceId);
        if (current == null) {
            throw new DomainConflictException(
                "READINESS_REQUIRED", "An exact readiness run is required.");
        }
        UUID reviewId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO procurement_reviews(
                id, invoice_id, invoice_version, package_version_id,
                readiness_run_id, decision, category, comment,
                authority_snapshot, reviewed_by_subject, correlation_id
            ) VALUES (?, ?, ?, ?, ?, 'CHANGES_REQUESTED', ?, ?, ?::jsonb, ?, ?)
            """, reviewId, invoiceId, invoice.currentVersion(),
            current.packageId(), current.readinessId(),
            clean(request.category(), 80), clean(request.summary(), 1000),
            canonical.write(authority(scope, "procurement.review")),
            subject, journal.correlationId());
        UUID queryId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO procurement_queries(
                id, review_id, invoice_id, category, owner_subject,
                due_at, status, correlation_id
            ) VALUES (?, ?, ?, ?, ?, ?, 'OPEN', ?)
            """, queryId, reviewId, invoiceId,
            clean(request.category(), 80), request.ownerId(),
            Timestamp.from(request.dueAt().toInstant()), journal.correlationId());
        long newVersion = transitionInvoice(
            invoice, "CHANGES_REQUESTED", request.expectedVersion());
        journal.event(invoice.monthId(), "f05.procurement.query.created.v1",
            "INVOICE", invoiceId, newVersion, map(
                "queryId", queryId, "reviewId", reviewId,
                "ownerSubject", request.ownerId(),
                "dueAt", request.dueAt()), subject);
        journal.audit(invoice.monthId(), "PROCUREMENT_QUERY_CREATED", "INVOICE",
            invoiceId, newVersion, "SUCCESS", request.reason(), subject,
            authority(scope, "procurement.review"),
            List.of(reference("PROCUREMENT_QUERY", queryId)));
        journal.remember(subject, "PROCUREMENT_QUERY", invoiceId,
            idempotencyKey, request, "PROCUREMENT_QUERY", queryId);
        return invoiceMutationView(invoiceId);
    }

    @Transactional
    public Map<String, Object> respondQuery(
        String subject,
        UUID queryId,
        String response,
        String idempotencyKey
    ) {
        QueryRow query = queryRow(queryId, true);
        var scope = authorization.requireInvoice(
            subject, query.invoiceId(), "finance.read",
            FinanceAuthorizationService.Party.ANY);
        if (!subject.equals(query.ownerSubject())) {
            throw new org.springframework.security.access.AccessDeniedException(
                "Only the assigned active query owner can respond.");
        }
        if (!"OPEN".equals(query.status())) {
            throw new DomainConflictException(
                "QUERY_NOT_OPEN", "Only an open query can receive a response.");
        }
        Map<String, Object> request = Map.of("response", response);
        UUID replay = journal.replay(
            subject, "PROCUREMENT_QUERY_RESPONSE", queryId,
            idempotencyKey, request);
        if (replay != null) {
            return queryView(subject, queryId);
        }
        UUID responseId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO procurement_query_responses(
                id, query_id, response_text, responded_by_subject
            ) VALUES (?, ?, ?, ?)
            """, responseId, queryId, clean(response, 2000), subject);
        jdbc.update("""
            UPDATE procurement_queries SET status = 'RESPONDED'
            WHERE id = ? AND status = 'OPEN'
            """, queryId);
        journal.event(query.monthId(), "f05.procurement.query.responded.v1",
            "PROCUREMENT_QUERY", queryId, 1,
            map("responseId", responseId), subject);
        journal.audit(query.monthId(), "PROCUREMENT_QUERY_RESPONDED",
            "PROCUREMENT_QUERY", queryId, 1L, "SUCCESS",
            "ASSIGNED_OWNER_RESPONSE", subject,
            authority(scope, "finance.read"),
            List.of(reference("QUERY_RESPONSE", responseId)));
        journal.remember(subject, "PROCUREMENT_QUERY_RESPONSE", queryId,
            idempotencyKey, request, "QUERY_RESPONSE", responseId);
        return queryView(subject, queryId);
    }

    @Transactional
    public Map<String, Object> closeQuery(
        String subject,
        UUID queryId,
        String decision,
        String reason,
        String idempotencyKey
    ) {
        QueryRow query = queryRow(queryId, true);
        var scope = authorization.requireInvoice(
            subject, query.invoiceId(), "procurement.review",
            FinanceAuthorizationService.Party.PROCUREMENT);
        String status = decision == null
            ? "" : decision.strip().toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("CLOSED", "CANCELLED").contains(status)
            || !Set.of("OPEN", "RESPONDED").contains(query.status())) {
            throw new DomainConflictException(
                "QUERY_TRANSITION_NOT_ALLOWED",
                "The requested query closure transition is not allowed.");
        }
        Map<String, Object> request = map(
            "decision", status, "reason", reason);
        UUID replay = journal.replay(
            subject, "PROCUREMENT_QUERY_CLOSE", queryId,
            idempotencyKey, request);
        if (replay != null) {
            return queryView(subject, queryId);
        }
        jdbc.update("""
            UPDATE procurement_queries
            SET status = ?, closed_by_subject = ?,
                closed_at = CURRENT_TIMESTAMP, close_reason = ?
            WHERE id = ?
            """, status, subject, clean(reason, 1000), queryId);
        journal.event(query.monthId(), "f05.procurement.query.closed.v1",
            "PROCUREMENT_QUERY", queryId, 2,
            map("status", status), subject);
        journal.audit(query.monthId(), "PROCUREMENT_QUERY_" + status,
            "PROCUREMENT_QUERY", queryId, 2L, "SUCCESS",
            clean(reason, 100), subject,
            authority(scope, "procurement.review"), List.of());
        journal.remember(subject, "PROCUREMENT_QUERY_CLOSE", queryId,
            idempotencyKey, request, "PROCUREMENT_QUERY", queryId);
        return queryView(subject, queryId);
    }

    @Transactional(noRollbackFor = DomainConflictException.class)
    public Map<String, Object> acceptException(
        String subject,
        UUID invoiceId,
        String ifMatch,
        String idempotencyKey,
        ProcurementExceptionInput request
    ) {
        var scope = authorization.requireInvoice(
            subject, invoiceId, "procurement.exception",
            FinanceAuthorizationService.Party.PROCUREMENT);
        exceptionValidity.expireInvoice(invoiceId, subject);
        requireIfMatch(ifMatch, request.expectedVersion());
        UUID replay = journal.replay(
            subject, "PROCUREMENT_EXCEPTION_REQUEST", invoiceId,
            idempotencyKey, request);
        if (replay != null) {
            return exceptionMutationView(replay);
        }
        InvoiceRow invoice = lockInvoice(invoiceId, request.expectedVersion());
        requireSeparation(subject, invoice);
        requireExceptionState(invoice.status());
        if (!request.validUntil().isAfter(OffsetDateTime.now(clock))) {
            throw new IllegalArgumentException(
                "Exception validity must end in the future.");
        }
        requireExactInputs(invoice, request.packageId(),
            request.packageVersion(), request.readinessRunId(), false);
        ReadinessFailure failure = readinessFailure(
            request.ruleId(), request.readinessRunId());
        if (failure == null || "PASS".equals(failure.result())
            || "PASS_WITH_DISCLOSED_NON_BLOCKING_EXCEPTION".equals(
                failure.result())) {
            throw new DomainConflictException(
                "FAILED_RULE_REQUIRED",
                "The exception must name an exact blocked readiness rule.");
        }
        FinancePolicyService.Policy policy =
            policies.active(invoice.engagementId(), subject);
        requireExceptionableRule(policy, failure.ruleCode());
        UUID reviewId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO procurement_reviews(
                id, invoice_id, invoice_version, package_version_id,
                readiness_run_id, decision, category, comment,
                authority_snapshot, reviewed_by_subject, correlation_id
            ) VALUES (?, ?, ?, ?, ?, 'EXCEPTION_REQUESTED',
                      'READINESS_EXCEPTION', ?, ?::jsonb, ?, ?)
            """, reviewId, invoiceId, invoice.currentVersion(),
            request.packageId(), request.readinessRunId(),
            clean(request.rationale(), 1000),
            canonical.write(exceptionAuthority(
                scope, subject, policy, "REQUEST")),
            subject, journal.correlationId());
        UUID exceptionId = UUID.randomUUID();
        UUID acceptedRunId = null;
        String exceptionStatus = policy.exceptionSecondApprovalRequired()
            ? "PENDING_SECOND_APPROVAL" : "PENDING_ACTIVATION";
        jdbc.update("""
            INSERT INTO procurement_exceptions(
                id, review_id, invoice_id, invoice_version,
                package_version_id, package_version, readiness_run_id,
                readiness_result_id, policy_version_id, policy_version,
                rationale, valid_until, status, second_approval_required,
                request_authority_snapshot, requested_by_subject,
                accepted_readiness_run_id, correlation_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                      ?::jsonb, ?, ?, ?)
            """, exceptionId, reviewId, invoiceId, invoice.currentVersion(),
            request.packageId(), request.packageVersion(),
            request.readinessRunId(), request.ruleId(), policy.id(),
            policy.version(), clean(request.rationale(), 1000),
            Timestamp.from(request.validUntil().toInstant()), exceptionStatus,
            policy.exceptionSecondApprovalRequired(),
            canonical.write(exceptionAuthority(
                scope, subject, policy, "REQUEST")),
            subject, acceptedRunId, journal.correlationId());
        if (!policy.exceptionSecondApprovalRequired()) {
            acceptedRunId = createExceptionReadinessRun(
                invoice, request.readinessRunId(), request.ruleId(),
                exceptionId, subject);
            jdbc.update("""
                UPDATE procurement_exceptions
                SET status = 'ACCEPTED',
                    accepted_readiness_run_id = ?
                WHERE id = ? AND status = 'PENDING_ACTIVATION'
                """, acceptedRunId, exceptionId);
            exceptionStatus = "ACCEPTED";
        }
        long newVersion = transitionInvoice(
            invoice,
            policy.exceptionSecondApprovalRequired()
                ? "EVIDENCE_PENDING" : "EXCEPTION_ACCEPTED",
            request.expectedVersion());
        if (acceptedRunId != null) {
            jdbc.update("""
                UPDATE invoices
                SET current_readiness_run_id = ?
                WHERE id = ?
                """, acceptedRunId, invoiceId);
        }
        String eventType = policy.exceptionSecondApprovalRequired()
            ? "f05.procurement.exception.requested.v1"
            : "f05.procurement.exception.accepted.v1";
        journal.event(invoice.monthId(), eventType,
            "INVOICE", invoiceId, newVersion, map(
                "exceptionId", exceptionId,
                "ruleId", request.ruleId(),
                "readinessRunId", acceptedRunId == null
                    ? request.readinessRunId() : acceptedRunId,
                "policyVersionId", policy.id(),
                "policyVersion", policy.version(),
                "status", exceptionStatus,
                "validUntil", request.validUntil()), subject);
        journal.audit(invoice.monthId(),
            policy.exceptionSecondApprovalRequired()
                ? "PROCUREMENT_EXCEPTION_REQUESTED"
                : "PROCUREMENT_EXCEPTION_ACCEPTED",
            "INVOICE", invoiceId, newVersion, "SUCCESS",
            policy.exceptionSecondApprovalRequired()
                ? "DISTINCT_SECOND_APPROVAL_PENDING"
                : "EXACT_RULE_EXCEPTION",
            subject, exceptionAuthority(scope, subject, policy, "REQUEST"),
            List.of(reference("READINESS_RESULT", request.ruleId()),
                reference("PROCUREMENT_EXCEPTION", exceptionId)));
        journal.remember(subject, "PROCUREMENT_EXCEPTION_REQUEST", invoiceId,
            idempotencyKey, request, "PROCUREMENT_EXCEPTION", exceptionId);
        return exceptionMutationView(exceptionId);
    }

    @Transactional(noRollbackFor = DomainConflictException.class)
    public Map<String, Object> approveException(
        String subject,
        UUID exceptionId,
        String ifMatch,
        String idempotencyKey,
        ProcurementExceptionApprovalInput request
    ) {
        UUID boundInvoiceId = jdbc.query("""
            SELECT invoice_id FROM procurement_exceptions WHERE id = ?
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
            exceptionId);
        if (boundInvoiceId == null) {
            throw new EntityNotFoundException(
                "Procurement exception not found.");
        }
        var scope = authorization.requireInvoice(
            subject, boundInvoiceId, "procurement.exception",
            FinanceAuthorizationService.Party.PROCUREMENT);
        exceptionValidity.expireInvoice(boundInvoiceId, subject);
        requireIfMatch(ifMatch, request.expectedVersion());
        UUID replay = journal.replay(
            subject, "PROCUREMENT_EXCEPTION_SECOND_APPROVAL", exceptionId,
            idempotencyKey, request);
        if (replay != null) {
            return exceptionMutationView(exceptionId);
        }
        ExceptionApprovalRow pending = lockPendingException(exceptionId);
        if (!boundInvoiceId.equals(request.invoiceId())
            || !pending.invoiceId().equals(request.invoiceId())
            || !pending.resultId().equals(request.ruleId())
            || !pending.readinessRunId().equals(request.readinessRunId())
            || !pending.packageId().equals(request.packageId())
            || pending.packageVersion() != request.packageVersion()
            || !pending.policyId().equals(request.policyVersionId())
            || pending.policyVersion() != request.policyVersion()) {
            throw new DomainConflictException(
                "EXCEPTION_APPROVAL_BINDING_MISMATCH",
                "Second approval must bind the exact requested invoice, package, readiness, rule and policy versions.");
        }
        if ("EXPIRED".equals(pending.status())) {
            throw new DomainConflictException(
                "EXCEPTION_EXPIRED",
                "The exception validity has expired.");
        }
        if (!"PENDING_SECOND_APPROVAL".equals(pending.status())) {
            throw new DomainConflictException(
                "EXCEPTION_NOT_PENDING_SECOND_APPROVAL",
                "The exception is not awaiting a second approval.");
        }
        if (!pending.validUntil().isAfter(OffsetDateTime.now(clock))) {
            throw new DomainConflictException(
                "EXCEPTION_EXPIRED",
                "The exception validity has expired.");
        }
        if (pending.invoiceOptimisticVersion() != request.expectedVersion()) {
            throw new DomainConflictException(
                "VERSION_MISMATCH", "Invoice version is stale.",
                pending.invoiceOptimisticVersion());
        }
        if (subject.equals(pending.requestedBy())) {
            throw new DomainConflictException(
                "SEPARATION_OF_DUTIES_VIOLATION",
                "The exception requester cannot provide its second approval.");
        }
        FinancePolicyService.Policy policy =
            policies.active(pending.engagementId(), subject);
        if (!policy.exceptionSecondApprovalRequired()
            || !policy.id().equals(pending.policyId())
            || policy.version() != pending.policyVersion()) {
            throw new DomainConflictException(
                "EXCEPTION_POLICY_VERSION_MISMATCH",
                "The effective policy no longer matches the pending exception.");
        }
        InvoiceRow invoice = new InvoiceRow(
            pending.invoiceId(), pending.monthId(), pending.engagementId(),
            pending.invoiceVersion(), pending.invoiceOptimisticVersion(),
            pending.invoiceState(), pending.invoiceCreator());
        requireExceptionState(invoice.status());
        requireExactInputs(invoice, pending.packageId(),
            pending.packageVersion(), pending.readinessRunId(), false);
        ReadinessFailure failure = readinessFailure(
            pending.resultId(), pending.readinessRunId());
        if (failure == null || "PASS".equals(failure.result())
            || "PASS_WITH_DISCLOSED_NON_BLOCKING_EXCEPTION".equals(
                failure.result())) {
            throw new DomainConflictException(
                "FAILED_RULE_REQUIRED",
                "The bound readiness rule is no longer blocked.");
        }
        requireExceptionableRule(policy, failure.ruleCode());

        UUID acceptedRunId = createExceptionReadinessRun(
            invoice, pending.readinessRunId(), pending.resultId(),
            exceptionId, subject);
        Map<String, Object> approvalAuthority =
            exceptionAuthority(scope, subject, policy, "SECOND_APPROVAL");
        jdbc.update("""
            UPDATE procurement_exceptions
            SET status = 'ACCEPTED',
                second_approver_subject = ?,
                second_approval_authority_snapshot = ?::jsonb,
                second_approved_at = CURRENT_TIMESTAMP,
                accepted_readiness_run_id = ?
            WHERE id = ? AND status = 'PENDING_SECOND_APPROVAL'
            """, subject, canonical.write(approvalAuthority),
            acceptedRunId, exceptionId);
        long newVersion = transitionInvoice(
            invoice, "EXCEPTION_ACCEPTED",
            request.expectedVersion());
        jdbc.update("""
            UPDATE invoices
            SET current_readiness_run_id = ?
            WHERE id = ?
            """, acceptedRunId, pending.invoiceId());
        journal.event(pending.monthId(),
            "f05.procurement.exception.second-approved.v1",
            "INVOICE", pending.invoiceId(), newVersion, map(
                "exceptionId", exceptionId,
                "requesterSubject", pending.requestedBy(),
                "secondApproverSubject", subject,
                "ruleId", pending.resultId(),
                "readinessRunId", acceptedRunId,
                "policyVersionId", policy.id(),
                "policyVersion", policy.version(),
                "validUntil", pending.validUntil()), subject);
        journal.audit(pending.monthId(),
            "PROCUREMENT_EXCEPTION_SECOND_APPROVED",
            "INVOICE", pending.invoiceId(), newVersion, "SUCCESS",
            "DISTINCT_AUTHENTICATED_SECOND_APPROVAL", subject,
            approvalAuthority,
            List.of(reference("PROCUREMENT_EXCEPTION", exceptionId),
                reference("READINESS_RESULT", pending.resultId()),
                reference("READINESS_RUN", acceptedRunId)));
        journal.remember(subject,
            "PROCUREMENT_EXCEPTION_SECOND_APPROVAL", exceptionId,
            idempotencyKey, request, "PROCUREMENT_EXCEPTION", exceptionId);
        return exceptionMutationView(exceptionId);
    }

    private void requireExceptionableRule(
        FinancePolicyService.Policy policy,
        String ruleCode
    ) {
        if (Set.of("INVOICE_DOCUMENT", "PACKAGE_MANIFEST")
                .contains(ruleCode)
            || !policy.exceptionableRules().contains(ruleCode)) {
            throw new DomainConflictException(
                "READINESS_RULE_NOT_EXCEPTIONABLE",
                "The bound readiness rule is not exceptionable under the effective policy.");
        }
    }

    public List<Map<String, Object>> payments(String subject, UUID invoiceId) {
        authorization.requireInvoice(
            subject, invoiceId, "finance.read",
            FinanceAuthorizationService.Party.ANY);
        exceptionValidity.expireInvoice(invoiceId, subject);
        return paymentRows(invoiceId);
    }

    @Transactional(noRollbackFor = DomainConflictException.class)
    public Map<String, Object> updatePayment(
        String subject,
        UUID invoiceId,
        String ifMatch,
        String idempotencyKey,
        PaymentUpdateInput request
    ) {
        var scope = authorization.requireInvoice(
            subject, invoiceId, "payment.update",
            FinanceAuthorizationService.Party.FINANCE);
        exceptionValidity.expireInvoice(invoiceId, subject);
        requireIfMatch(ifMatch, request.expectedVersion());
        UUID replay = journal.replay(
            subject, "PAYMENT_UPDATE", invoiceId, idempotencyKey, request);
        if (replay != null) {
            return invoiceMutationView(invoiceId);
        }
        InvoiceRow invoice = lockInvoice(invoiceId, request.expectedVersion());
        if (!Set.of(
                "APPROVED_FOR_PROCESSING", "PAYMENT_INITIATED", "ON_HOLD")
            .contains(invoice.status())) {
            throw new DomainConflictException(
                "PROCUREMENT_APPROVAL_REQUIRED",
                "Payment status can advance only after exact evidence is approved for processing.");
        }
        Boolean exactApproval = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1
                FROM procurement_reviews review
                JOIN evidence_package_versions package
                  ON package.id = review.package_version_id
                JOIN invoice_readiness_runs readiness
                  ON readiness.id = review.readiness_run_id
                WHERE review.invoice_id = ?
                  AND review.invoice_version = ?
                  AND review.decision = 'APPROVED_FOR_PROCESSING'
                  AND package.id = (
                      SELECT current_package_version_id
                      FROM invoices WHERE id = ?)
                  AND package.invoice_id = ?
                  AND package.invoice_version = ?
                  AND package.status = 'CURRENT'
                  AND readiness.id = (
                      SELECT current_readiness_run_id
                      FROM invoices WHERE id = ?)
                  AND readiness.invoice_id = ?
                  AND readiness.invoice_version = ?
                  AND readiness.package_version_id = package.id
                  AND readiness.current_result
            )
            """, Boolean.class, invoiceId, invoice.currentVersion(),
            invoiceId, invoiceId, invoice.currentVersion(),
            invoiceId, invoiceId, invoice.currentVersion());
        if (!Boolean.TRUE.equals(exactApproval)) {
            throw new DomainConflictException(
                "EXACT_APPROVED_LINEAGE_REQUIRED",
                "Payment status requires the exact approved invoice, package and readiness lineage.");
        }
        String prior = jdbc.query("""
            SELECT status FROM payment_status_history
            WHERE invoice_id = ?
            ORDER BY sequence_number DESC
            LIMIT 1
            """, rs -> rs.next() ? rs.getString(1) : null, invoiceId);
        requirePaymentTransition(prior, request.status());
        requirePaymentDates(request);
        Integer priorSequence = jdbc.queryForObject("""
            SELECT COALESCE(MAX(sequence_number), 0)
            FROM payment_status_history
            WHERE invoice_id = ?
            """, Integer.class, invoiceId);
        int sequence = (priorSequence == null ? 0 : priorSequence) + 1;
        UUID paymentId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO payment_status_history(
                id, invoice_id, sequence_number, status, sanitized_comment,
                external_reference, status_at, expected_payment_date,
                actual_payment_date, source, recorded_by_subject,
                correlation_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'MANUAL', ?, ?)
            """, paymentId, invoiceId, sequence, request.status(),
            cleanVisibleComment(request.comment()),
            clean(request.externalReference(), 160),
            Timestamp.from(request.statusAt().toInstant()),
            request.expectedPaymentDate(), request.actualPaymentDate(),
            subject, journal.correlationId());
        String invoiceStatus = switch (request.status()) {
            case "PAYMENT_INITIATED" -> "PAYMENT_INITIATED";
            case "PAID" -> "PAID";
            case "ON_HOLD", "PAYMENT_FAILED" -> "ON_HOLD";
            default -> invoice.status();
        };
        long newVersion = transitionInvoice(
            invoice, invoiceStatus, request.expectedVersion());
        journal.event(invoice.monthId(), "f05.payment.status.changed.v1",
            "INVOICE", invoiceId, newVersion, map(
                "paymentEventId", paymentId,
                "sequence", sequence,
                "status", request.status(),
                "source", "MANUAL"), subject);
        journal.audit(invoice.monthId(), "PAYMENT_STATUS_APPENDED", "INVOICE",
            invoiceId, newVersion, "SUCCESS", request.status(), subject,
            authority(scope, "payment.update"),
            List.of(reference("PAYMENT_STATUS", paymentId)));
        journal.remember(subject, "PAYMENT_UPDATE", invoiceId,
            idempotencyKey, request, "PAYMENT_STATUS", paymentId);
        return invoiceMutationView(invoiceId);
    }

    public Map<String, Object> dashboard(String subject) {
        List<UUID> engagements = authorizedEngagements(
            subject, "finance.read");
        if (engagements.isEmpty()) {
            throw new EntityNotFoundException("Finance scope not found.");
        }
        DashboardCounts counts = jdbc.query("""
            SELECT
                COUNT(*) FILTER (
                    WHERE tower.readiness_status LIKE 'PASS%'
                       OR tower.readiness_status =
                          'EXCEPTION_ACCEPTED_BY_PROCUREMENT'
                ),
                COUNT(*) FILTER (WHERE tower.readiness_status IS NOT NULL),
                COUNT(*) FILTER (
                    WHERE EXISTS (
                        SELECT 1
                        FROM effective_f05_certification_handoffs handoff
                        WHERE handoff.engagement_month_id =
                              tower.engagement_month_id
                          AND handoff.effective_status <> 'INVALIDATED'
                    )
                ),
                COUNT(*) FILTER (
                    WHERE EXISTS (
                        SELECT 1
                        FROM f05_certification_handoffs handoff
                        WHERE handoff.engagement_month_id =
                              tower.engagement_month_id
                    )
                ),
                COUNT(*) FILTER (WHERE tower.payment_status IS NOT NULL),
                COUNT(*) FILTER (
                    WHERE tower.invoice_status IN (
                        'SUBMITTED_TO_PROCUREMENT', 'PROCUREMENT_REVIEW'
                    )
                ),
                COUNT(*) FILTER (WHERE tower.reopened_or_invalidated)
            FROM f05_control_tower tower
            WHERE tower.engagement_id = ANY (?::uuid[])
            """, rs -> rs.next() ? new DashboardCounts(
                rs.getLong(1), rs.getLong(2), rs.getLong(3),
                rs.getLong(4), rs.getLong(5), rs.getLong(6),
                rs.getLong(7)) : new DashboardCounts(0, 0, 0, 0, 0, 0, 0),
            (Object) engagements.toArray(UUID[]::new));
        List<Map<String, Object>> definitions = metricDefinitions();
        List<Map<String, Object>> metrics = List.of(
            metric(definitions, "INVOICE_READINESS", counts.ready(),
                counts.readinessEvaluated() > 0),
            metric(definitions, "CONFIRMATION_COMPLETION",
                counts.confirmed(), counts.confirmationRecorded() > 0),
            metric(definitions, "PAYMENT_STATUS", counts.payment(),
                counts.payment() > 0));
        return map(
            "personaLabel", "Scoped finance and Procurement",
            "refreshedAt", OffsetDateTime.now(clock),
            "freshness", "CURRENT",
            "metrics", metrics,
            "queues", List.of(
                queue("PROCUREMENT_REVIEW", "Awaiting Procurement review",
                    counts.review(), "/finance/procurement"),
                queue("REOPENED", "Reopened or invalidated",
                    counts.reopened(),
                    "/finance/procurement"),
                queue("PAYMENT", "Payment status available",
                    counts.payment(), "/finance/invoices")),
            "permissions", governancePermissions(subject, engagements));
    }

    public Map<String, Object> reports(String subject) {
        return reports(subject, null);
    }

    public Map<String, Object> reports(String subject, String cursor) {
        List<UUID> engagements = authorizedEngagements(subject, "finance.read");
        if (engagements.isEmpty()) {
            throw new EntityNotFoundException("Finance scope not found.");
        }
        FinancePageCursorCodec.Cursor decoded =
            decodeCursor(cursor, "report-exports", subject, engagements);
        Instant snapshotAt = decoded == null
            ? clock.instant() : decoded.snapshotAt();
        OffsetDateTime lastRequestedAt = decoded == null ? null
            : OffsetDateTime.parse(decoded.lastSortValue());
        UUID lastId = decoded == null ? null : decoded.lastId();
        List<Map<String, Object>> exports = jdbc.query("""
            SELECT id, organization_id, engagement_id, report_code,
                   report_version, format, filters::text, status, progress,
                   row_count, result_hash, source_freshness_at,
                   snapshot_label, requested_at, completed_at, expires_at,
                   correlation_id
            FROM f05_report_exports
            WHERE requested_by_subject = ?
              AND engagement_id = ANY (?::uuid[])
              AND requested_at <= ?
              AND (
                  ?::timestamptz IS NULL
                  OR (requested_at, id) < (?::timestamptz, ?::uuid)
              )
            ORDER BY requested_at DESC, id DESC
            LIMIT 51
            """, (rs, index) -> exportMap(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8),
                rs.getInt(9), (Long) rs.getObject(10), rs.getString(11),
                offset(rs.getTimestamp(12)), rs.getString(13),
                offset(rs.getTimestamp(14)), offset(rs.getTimestamp(15)),
                offset(rs.getTimestamp(16)), rs.getObject(17, UUID.class)),
            subject, engagements.toArray(UUID[]::new),
            Timestamp.from(snapshotAt),
            lastRequestedAt == null ? null
                : Timestamp.from(lastRequestedAt.toInstant()),
            lastRequestedAt == null ? null
                : Timestamp.from(lastRequestedAt.toInstant()),
            lastId);
        long total = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM f05_report_exports
            WHERE requested_by_subject = ?
              AND engagement_id = ANY (?::uuid[])
              AND requested_at <= ?
            """, Long.class, subject, engagements.toArray(UUID[]::new),
            Timestamp.from(snapshotAt));
        return map(
            "permissions", governancePermissions(subject, engagements),
            "definitions", reportDefinitions().stream()
                .filter(definition -> engagements.stream().anyMatch(
                    engagementId -> hasScopedPermission(
                        subject, engagementId,
                        reportPermission(String.valueOf(
                            definition.get("reportId"))))))
                .toList(),
            "exports", cursorPage(
                exports, total, "report-exports", subject,
                engagements, snapshotAt, "requestedAt", "exportId"));
    }

    @Transactional
    public Map<String, Object> requestExport(
        String subject,
        String idempotencyKey,
        CreateExportInput request
    ) {
        ReportDefinition definition = reportDefinition(request.reportId());
        if (definition == null
            || !definition.version().equals(request.reportVersion())
            || !definition.formats().contains(request.format())
            || !Set.of("CURRENT", "SNAPSHOT").contains(request.temporalMode())) {
            throw new IllegalArgumentException(
                "Unsupported report, version, format or temporal mode.");
        }
        UUID engagementId = requestedEngagement(
            request.filters(), authorizedEngagements(subject, "report.export"));
        var scope = authorization.requireEngagement(
            subject, engagementId, "report.export");
        authorization.requireEngagement(
            subject, engagementId, reportPermission(definition.id()));
        UUID replay = journal.replay(
            subject, "REPORT_EXPORT", engagementId, idempotencyKey, request);
        if (replay != null) {
            return exportStatus(subject, replay);
        }
        UUID organizationId = actorOrganization(
            subject, engagementId, scope);
        UUID exportId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO f05_report_exports(
                id, organization_id, engagement_id, report_code,
                report_version, format, filters, status, progress,
                source_freshness_at, snapshot_label, requested_by_subject,
                authority_snapshot, correlation_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, 'PENDING', 0,
                      CURRENT_TIMESTAMP, ?, ?, ?::jsonb, ?)
            """, exportId, organizationId, engagementId,
            request.reportId(), request.reportVersion(), request.format(),
            canonical.write(request.filters()), request.temporalMode(),
            subject,
            canonical.write(exportAuthority(
                scope, reportPermission(definition.id()), subject,
                organizationId)),
            journal.correlationId());
        journal.event(null, "f05.export.requested.v1", "REPORT_EXPORT",
            exportId, 1, map(
                "reportId", request.reportId(),
                "reportVersion", request.reportVersion(),
                "format", request.format(),
                "temporalMode", request.temporalMode(),
                "providerStatus", storage.configurationStatus()), subject);
        journal.audit(null, "REPORT_EXPORT_REQUESTED", "REPORT_EXPORT",
            exportId, 1L, "SUCCESS", "ASYNC_EXPORT_QUEUED",
            subject, authority(scope, "report.export"), List.of());
        journal.remember(subject, "REPORT_EXPORT", engagementId,
            idempotencyKey, request, "REPORT_EXPORT", exportId);
        return exportStatus(subject, exportId);
    }

    public Map<String, Object> exportStatus(String subject, UUID exportId) {
        requireExportAccess(
            subject, exportId, "finance.read",
            "REPORT_EXPORT_STATUS_DENIED");
        Map<String, Object> value = jdbc.query("""
            SELECT id, organization_id, engagement_id, report_code,
                   report_version, format, filters::text, status, progress,
                   row_count, result_hash, source_freshness_at,
                   snapshot_label, requested_at, completed_at, expires_at,
                   correlation_id
            FROM f05_report_exports
            WHERE id = ?
            """, rs -> rs.next() ? exportMap(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8),
                rs.getInt(9), (Long) rs.getObject(10), rs.getString(11),
                offset(rs.getTimestamp(12)), rs.getString(13),
                offset(rs.getTimestamp(14)), offset(rs.getTimestamp(15)),
                offset(rs.getTimestamp(16)), rs.getObject(17, UUID.class))
                : null, exportId);
        if (value == null) {
            throw new EntityNotFoundException("Export not found.");
        }
        return value;
    }

    @Transactional
    public Map<String, Object> replayExport(
        String subject,
        UUID exportId,
        String reason,
        String idempotencyKey
    ) {
        var scope = requireExportAccess(
            subject, exportId, "report.export",
            "REPORT_EXPORT_REPLAY_DENIED").scope();
        Map<String, Object> request = map("reason", clean(reason, 1000));
        UUID replay = journal.replay(
            subject, "REPORT_EXPORT_REPLAY", exportId,
            idempotencyKey, request);
        if (replay != null) {
            return exportStatus(subject, exportId);
        }
        int changed = jdbc.update("""
            UPDATE f05_report_exports
            SET status = 'PENDING', progress = 0,
                retry_cycle_attempt_count = 0,
                next_attempt_at = CURRENT_TIMESTAMP,
                lease_owner = NULL, lease_expires_at = NULL,
                last_error_code = NULL
            WHERE id = ? AND status IN ('FAILED', 'DEAD_LETTER')
            """, exportId);
        if (changed != 1) {
            throw new DomainConflictException(
                "EXPORT_REPLAY_NOT_ALLOWED",
                "Only a failed or dead-letter export can be replayed.");
        }
        journal.event(null, "f05.export.replayed.v1", "REPORT_EXPORT",
            exportId, 2, map("reason", clean(reason, 100)), subject);
        journal.audit(null, "REPORT_EXPORT_REPLAYED", "REPORT_EXPORT",
            exportId, 2L, "SUCCESS", clean(reason, 100), subject,
            authority(scope, "report.export"), List.of());
        journal.remember(subject, "REPORT_EXPORT_REPLAY", exportId,
            idempotencyKey, request, "REPORT_EXPORT", exportId);
        return exportStatus(subject, exportId);
    }

    @Transactional
    public ExportDownloadResult exportDownload(String subject, UUID exportId) {
        var scope = requireExportAccess(
            subject, exportId, "report.export",
            "REPORT_EXPORT_DOWNLOAD_DENIED").scope();
        ExportDownload row = jdbc.query("""
            SELECT export.engagement_id, export.status,
                   export.result_artifact_id, export.result_hash,
                   artifact.scan_status, artifact.media_type,
                   artifact.safe_name, export.expires_at
            FROM f05_report_exports export
            LEFT JOIN f05_private_artifacts artifact
              ON artifact.id = export.result_artifact_id
            WHERE export.id = ?
            """, rs -> rs.next() ? new ExportDownload(
                rs.getObject(1, UUID.class), rs.getString(2),
                rs.getObject(3, UUID.class), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7),
                offset(rs.getTimestamp(8))) : null, exportId);
        if (row == null) {
            throw new EntityNotFoundException("Export not found.");
        }
        if (!"READY".equals(row.status())
            || !"PASSED".equals(row.scanStatus())
            || row.artifactId() == null
            || row.expiresAt() == null
            || !row.expiresAt().isAfter(OffsetDateTime.now(clock))) {
            throw new DomainConflictException(
                "EXPORT_NOT_DOWNLOADABLE",
                "The private export is not ready, scan-passed and unexpired.");
        }
        byte[] content = storage.read(row.artifactId());
        if (!canonical.sha256Bytes(content).equals(row.resultHash())) {
            throw new DomainConflictException(
                "EXPORT_INTEGRITY_FAILED",
                "The private export checksum did not match.");
        }
        journal.audit(null, "REPORT_EXPORT_DOWNLOADED", "REPORT_EXPORT",
            exportId, 1L, "SUCCESS", "AUTHORIZED_DOWNLOAD",
            subject, authority(scope, "report.export"),
            List.of(reference("PRIVATE_ARTIFACT", row.artifactId())));
        return new ExportDownloadResult(
            content, row.mediaType(), row.safeName());
    }

    private ExportAccess requireExportAccess(
        String subject,
        UUID exportId,
        String routePermission,
        String deniedAction
    ) {
        ExportHeader header = jdbc.query("""
            SELECT engagement_id, report_code
            FROM f05_report_exports
            WHERE id = ?
            """, rs -> rs.next() ? new ExportHeader(
                rs.getObject(1, UUID.class), rs.getString(2)) : null,
            exportId);
        if (header == null || header.engagementId() == null) {
            throw new EntityNotFoundException("Export not found.");
        }
        String requiredReportPermission =
            reportDefinition(header.reportCode()) == null
                ? null : reportPermission(header.reportCode());
        if (requiredReportPermission == null) {
            recordExportDenial(
                subject, exportId, deniedAction, header.reportCode(),
                routePermission, "UNSUPPORTED_REPORT_DEFINITION");
            throw new AccessDeniedException(
                "The authenticated identity lacks scoped export authority.");
        }
        try {
            FinanceAuthorizationService.Scope scope =
                authorization.requireEngagement(
                    subject, header.engagementId(), routePermission);
            authorization.requireEngagement(
                subject, header.engagementId(),
                requiredReportPermission);
            return new ExportAccess(header, scope);
        } catch (AccessDeniedException denied) {
            recordExportDenial(
                subject, exportId, deniedAction, header.reportCode(),
                routePermission, requiredReportPermission);
            throw new AccessDeniedException(
                "The authenticated identity lacks scoped export authority.");
        }
    }

    private void recordExportDenial(
        String subject,
        UUID exportId,
        String action,
        String reportCode,
        String routePermission,
        String reportPermission
    ) {
        try {
            denialRecorder.recordExportDenied(
                subject, exportId, action, reportCode,
                routePermission, reportPermission);
        } catch (RuntimeException ignored) {
            // Authorization remains fail-closed if evidence persistence is
            // temporarily unavailable.
        }
    }

    private List<UUID> authorizedEngagements(String subject, String permission) {
        LocalDate today = LocalDate.now(clock);
        return jdbc.query("""
            SELECT DISTINCT engagement.id
            FROM engagements engagement
            JOIN organizations organization
              ON organization.id IN (
                  engagement.vendor_organization_id,
                  engagement.client_organization_id,
                  engagement.procurement_organization_id,
                  engagement.finance_organization_id)
            JOIN memberships membership
              ON membership.organization_id = organization.id
            JOIN user_profiles profile
              ON profile.id = membership.user_profile_id
            JOIN role_assignments assignment
              ON assignment.user_profile_id = profile.id
             AND assignment.organization_id = organization.id
            JOIN role_permissions role_permission
              ON role_permission.role_id = assignment.role_id
            JOIN permissions granted
              ON granted.id = role_permission.permission_id
            WHERE profile.identity_subject = ?
              AND profile.status = 'ACTIVE'
              AND membership.status = 'ACTIVE'
              AND membership.valid_from <= ?
              AND (membership.valid_to IS NULL OR membership.valid_to >= ?)
              AND assignment.status = 'ACTIVE'
              AND assignment.valid_from <= ?
              AND (assignment.valid_to IS NULL OR assignment.valid_to >= ?)
              AND granted.code = ?
              AND (
                  (assignment.scope_type = 'ORGANIZATION'
                   AND assignment.scope_id = organization.id)
                  OR (assignment.scope_type = 'ENGAGEMENT'
                      AND assignment.scope_id = engagement.id)
                  OR (assignment.scope_type = 'PROJECT'
                      AND EXISTS (
                          SELECT 1 FROM projects project
                          WHERE project.id = assignment.scope_id
                            AND project.engagement_id = engagement.id)))
            ORDER BY engagement.id
            """, (rs, rowNum) -> rs.getObject(1, UUID.class),
            subject, today, today, today, today, permission);
    }

    private Map<String, Object> controlTowerRow(
        UUID monthId,
        LocalDate monthStart,
        String engagementLabel,
        UUID packageId,
        Integer packageVersion,
        String packageStatus,
        UUID invoiceId,
        String invoiceNumber,
        String invoiceStatus,
        String readinessStatus,
        OffsetDateTime readinessAt,
        String paymentStatus,
        OffsetDateTime paymentAt,
        boolean invalidated
    ) {
        String staleState = invalidated ? "STALE" : "COMPLETE";
        String readinessState = readinessStatus == null
            ? "BLOCKING"
            : readinessStatus.contains("EXCEPTION")
                ? "EXCEPTION_ACCEPTED"
                : readinessStatus.startsWith("PASS") ? "COMPLETE" : "BLOCKING";
        String packageState = packageId == null ? "BLOCKING"
            : invalidated || "INVALIDATED".equals(packageStatus)
                ? "STALE" : "COMPLETE";
        String invoiceCell = invoiceId == null ? "BLOCKING"
            : Set.of("CHANGES_REQUESTED", "ON_HOLD", "REJECTED")
                .contains(invoiceStatus) ? "WARNING" : "COMPLETE";
        String paymentState = paymentStatus == null ? "NOT_APPLICABLE"
            : Set.of("PAYMENT_FAILED", "ON_HOLD").contains(paymentStatus)
                ? "WARNING" : "COMPLETE";
        List<Map<String, Object>> cells = List.of(
            matrixCell("ROSTER", "Roster", staleState,
                "Vendor delivery manager", null, invalidated, "F04 roster snapshot",
                "/certification"),
            matrixCell("ATTENDANCE", "Attendance", staleState,
                "Attendance close authority", null, invalidated, "F04 attendance snapshot",
                "/attendance/month-close"),
            matrixCell("PLAN", "Approved plan", staleState,
                "Delivery governance", null, invalidated, "Frozen plan baseline",
                "/delivery/plans"),
            matrixCell("LINEAR", "Linear snapshot", staleState,
                "Delivery integration owner", null, invalidated, "F04 Linear snapshot",
                "/delivery"),
            matrixCell("CERTIFICATION", "Certification", staleState,
                "Client product owner", null, invalidated, "F04 certification",
                "/certification"),
            matrixCell("CONFIRMATION", "Confirmation", staleState,
                "Eligible confirmer", null, invalidated, "Verified F04 handoff",
                "/confirmation"),
            matrixCell("PACKAGE", "Package", packageState,
                "Evidence package owner",
                packageVersion == null ? null : packageVersion.toString(),
                invalidated, "F05 immutable package",
                "/finance?monthId=" + monthId),
            matrixCell("INVOICE", "Invoice", invoiceCell,
                "Vendor invoice owner", invoiceStatus,
                invalidated, "F05 invoice version",
                invoiceId == null ? "/finance" : "/finance?invoiceId=" + invoiceId),
            matrixCell("PAYMENT", "Payment", paymentState,
                "Finance AP", paymentStatus, false,
                "Append-only AP/ERP status", "/finance"));
        return map(
            "monthId", monthId,
            "monthLabel", monthStart.toString(),
            "engagementLabel", engagementLabel,
            "packageId", packageId,
            "packageVersion", packageVersion,
            "packageState", packageStatus,
            "invoiceId", invoiceId,
            "invoiceNumber", invoiceNumber,
            "invoiceState", invoiceStatus,
            "readiness", readinessStatus,
            "readinessEvaluatedAt", readinessAt,
            "paymentStatus", paymentStatus,
            "paymentStatusAt", paymentAt,
            "reopenedOrInvalidated", invalidated,
            "freshness", invalidated ? "STALE" : "CURRENT",
            "queue", invalidated ? "REOPENED"
                : invoiceId == null ? "INVOICE_REQUIRED"
                : Set.of("SUBMITTED_TO_PROCUREMENT", "PROCUREMENT_REVIEW")
                    .contains(invoiceStatus) ? "PROCUREMENT_REVIEW"
                    : paymentStatus == null ? "PAYMENT_PENDING" : "MONITOR",
            "ageDays", readinessAt == null ? null
                : java.time.Duration.between(
                    readinessAt, OffsetDateTime.now(clock)).toDays(),
            "cells", cells,
            "ownerDisplay", "Scoped finance owner",
            "remediationLabel", invalidated ? "Regenerate current evidence" : null);
    }

    private Map<String, Object> matrixCell(
        String key,
        String label,
        String state,
        String owner,
        String version,
        boolean stale,
        String source,
        String action
    ) {
        return map(
            "key", key, "label", label, "state", state,
            "ownerDisplay", owner, "version", version,
            "freshness", stale ? "STALE" : "CURRENT",
            "temporalMode", "SNAPSHOT",
            "sourceLabel", source,
            "actionPath", action);
    }

    private List<String> governancePermissions(
        String subject,
        List<UUID> engagements
    ) {
        Map<String, String> mapping = Map.of(
            "finance.read", "INVOICE_VIEW",
            "procurement.review", "PROCUREMENT_REVIEW",
            "procurement.exception", "PROCUREMENT_EXCEPTION",
            "payment.update", "PAYMENT_UPDATE",
            "report.export", "REPORT_EXPORT",
            "finance.audit.read", "EVIDENCE_PACKAGE_ACCESS_AUDIT");
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            if (engagements.stream().anyMatch(engagement ->
                hasScopedPermission(subject, engagement, entry.getKey()))) {
                result.add(entry.getValue());
            }
        }
        if (result.contains("PROCUREMENT_REVIEW")) {
            result.add("PROCUREMENT_QUERY");
        }
        if (result.contains("INVOICE_VIEW")) {
            result.add("PAYMENT_VIEW");
            result.add("REPORT_VIEW");
        }
        return result.stream().distinct().sorted().toList();
    }

    private Map<String, Object> page(List<?> items) {
        return map("items", items, "nextCursor", null, "totalCount", items.size());
    }

    private FinancePageCursorCodec.Cursor decodeCursor(
        String encoded,
        String resource,
        String subject,
        List<UUID> engagements
    ) {
        return encoded == null || encoded.isBlank() ? null
            : cursors.decode(encoded, resource, subject, engagements);
    }

    private Map<String, Object> cursorPage(
        List<Map<String, Object>> queried,
        long totalCount,
        String resource,
        String subject,
        List<UUID> engagements,
        Instant snapshotAt,
        String sortField,
        String idField
    ) {
        boolean hasNext = queried.size() > 50;
        List<Map<String, Object>> items = hasNext
            ? List.copyOf(queried.subList(0, 50)) : List.copyOf(queried);
        String nextCursor = null;
        if (hasNext) {
            Map<String, Object> last = items.getLast();
            nextCursor = cursors.encode(
                resource, subject, engagements, snapshotAt,
                String.valueOf(last.get(sortField)),
                UUID.fromString(String.valueOf(last.get(idField))));
        }
        return map(
            "items", items,
            "nextCursor", nextCursor,
            "totalCount", totalCount);
    }

    private void requireIfMatch(String ifMatch, long expected) {
        if (ifMatch == null) {
            throw new IllegalArgumentException("If-Match is required.");
        }
        String normalized = ifMatch.strip().replaceFirst("^W/", "").replace("\"", "");
        try {
            if (Long.parseLong(normalized) != expected) {
                throw new DomainConflictException(
                    "VERSION_MISMATCH", "If-Match does not match the request version.");
            }
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("If-Match must be a numeric version.", exception);
        }
    }

    private InvoiceRow lockInvoice(UUID invoiceId, long expectedVersion) {
        InvoiceRow row = jdbc.query("""
            SELECT invoice.id, invoice.engagement_month_id, month.engagement_id,
                   invoice.current_version, invoice.optimistic_version,
                   invoice.status, invoice.created_by_subject
            FROM invoices invoice
            JOIN engagement_months month
              ON month.id = invoice.engagement_month_id
            WHERE invoice.id = ?
            FOR UPDATE
            """, rs -> rs.next() ? new InvoiceRow(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getInt(4), rs.getLong(5),
                rs.getString(6), rs.getString(7)) : null, invoiceId);
        if (row == null) {
            throw new EntityNotFoundException("Invoice not found.");
        }
        if (row.optimisticVersion() != expectedVersion) {
            throw new DomainConflictException(
                "VERSION_MISMATCH", "Invoice version is stale.", row.optimisticVersion());
        }
        return row;
    }

    private Map<String, Object> invoiceMutationView(UUID invoiceId) {
        return jdbc.query("""
            SELECT id, engagement_month_id, invoice_number, status,
                   current_version, optimistic_version, updated_at
            FROM invoices WHERE id = ?
            """, rs -> {
                if (!rs.next()) {
                    throw new EntityNotFoundException("Invoice not found.");
                }
                return map(
                    "invoiceId", rs.getObject(1, UUID.class),
                    "monthId", rs.getObject(2, UUID.class),
                    "invoiceNumber", rs.getString(3),
                    "state", rs.getString(4),
                    "documentVersion", rs.getInt(5),
                    "version", rs.getLong(6),
                    "etag", rs.getLong(6),
                    "updatedAt", offset(rs.getTimestamp(7)));
            }, invoiceId);
    }

    private Map<String, Object> exceptionMutationView(UUID exceptionId) {
        return jdbc.query("""
            SELECT exception.id, exception.invoice_id, exception.status,
                   exception.readiness_result_id,
                   exception.readiness_run_id, exception.package_version_id,
                   exception.package_version, exception.policy_version_id,
                   exception.policy_version, exception.valid_until,
                   exception.requested_by_subject,
                   exception.second_approver_subject,
                   exception.accepted_readiness_run_id,
                   invoice.status, invoice.optimistic_version,
                   exception.requested_at, exception.second_approved_at,
                   exception.expired_at
            FROM procurement_exceptions exception
            JOIN invoices invoice ON invoice.id = exception.invoice_id
            WHERE exception.id = ?
            """, rs -> {
                if (!rs.next()) {
                    throw new EntityNotFoundException(
                        "Procurement exception not found.");
                }
                return map(
                    "exceptionId", rs.getObject(1, UUID.class),
                    "invoiceId", rs.getObject(2, UUID.class),
                    "exceptionStatus", rs.getString(3),
                    "ruleId", rs.getObject(4, UUID.class),
                    "requestedReadinessRunId", rs.getObject(5, UUID.class),
                    "packageId", rs.getObject(6, UUID.class),
                    "packageVersion", rs.getInt(7),
                    "policyVersionId", rs.getObject(8, UUID.class),
                    "policyVersion", rs.getInt(9),
                    "validUntil", offset(rs.getTimestamp(10)),
                    "requestedBySubject", rs.getString(11),
                    "secondApproverSubject", rs.getString(12),
                    "acceptedReadinessRunId", rs.getObject(13, UUID.class),
                    "state", rs.getString(14),
                    "version", rs.getLong(15),
                    "etag", rs.getLong(15),
                    "requestedAt", offset(rs.getTimestamp(16)),
                    "secondApprovedAt", offset(rs.getTimestamp(17)),
                    "expiredAt", offset(rs.getTimestamp(18)));
            }, exceptionId);
    }

    private ExceptionApprovalRow lockPendingException(UUID exceptionId) {
        ExceptionApprovalRow row = jdbc.query("""
            SELECT exception.invoice_id, exception.readiness_result_id,
                   exception.readiness_run_id, exception.package_version_id,
                   exception.package_version, exception.policy_version_id,
                   exception.policy_version, exception.status,
                   exception.valid_until, exception.requested_by_subject,
                   invoice.engagement_month_id, month.engagement_id,
                   invoice.current_version, invoice.optimistic_version,
                   invoice.status, invoice.created_by_subject
            FROM procurement_exceptions exception
            JOIN invoices invoice ON invoice.id = exception.invoice_id
            JOIN engagement_months month
              ON month.id = invoice.engagement_month_id
            WHERE exception.id = ?
            FOR UPDATE OF exception, invoice
            """, rs -> rs.next() ? new ExceptionApprovalRow(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                rs.getInt(5), rs.getObject(6, UUID.class), rs.getInt(7),
                rs.getString(8), offset(rs.getTimestamp(9)), rs.getString(10),
                rs.getObject(11, UUID.class), rs.getObject(12, UUID.class),
                rs.getInt(13), rs.getLong(14), rs.getString(15),
                rs.getString(16)) : null, exceptionId);
        if (row == null) {
            throw new EntityNotFoundException(
                "Procurement exception not found.");
        }
        return row;
    }

    private void requireSeparation(String subject, InvoiceRow invoice) {
        if (subject.equals(invoice.createdBySubject())) {
            throw new DomainConflictException(
                "SEPARATION_OF_DUTIES_VIOLATION",
                "The invoice creator cannot perform its Procurement review.");
        }
    }

    private void requireReviewState(String state) {
        if (!Set.of(
                "SUBMITTED_TO_PROCUREMENT", "PROCUREMENT_REVIEW",
                "CHANGES_REQUESTED", "ON_HOLD", "REJECTED",
                "EXCEPTION_ACCEPTED")
            .contains(state)) {
            throw new DomainConflictException(
                "PROCUREMENT_REVIEW_NOT_ALLOWED",
                "The invoice is not in a reviewable state.");
        }
    }

    private void requireExceptionState(String state) {
        if (!Set.of(
                "EVIDENCE_PENDING", "SUBMITTED_TO_PROCUREMENT",
                "PROCUREMENT_REVIEW", "CHANGES_REQUESTED", "ON_HOLD",
                "REJECTED", "EXCEPTION_ACCEPTED")
            .contains(state)) {
            throw new DomainConflictException(
                "PROCUREMENT_EXCEPTION_NOT_ALLOWED",
                "The invoice does not have a reviewable blocked readiness result.");
        }
    }

    private void requireExactInputs(
        InvoiceRow invoice,
        UUID packageId,
        int packageVersion,
        UUID readinessId,
        boolean mustBeEligible
    ) {
        Boolean valid = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1
                FROM invoice_readiness_runs readiness
                JOIN evidence_package_versions package
                  ON package.id = readiness.package_version_id
                WHERE readiness.id = ?
                  AND readiness.invoice_id = ?
                  AND readiness.invoice_version = ?
                  AND readiness.current_result
                  AND (? = FALSE OR readiness.eligible)
                  AND package.id = ?
                  AND package.version = ?
                  AND package.invoice_id = readiness.invoice_id
                  AND package.invoice_id = ?
                  AND package.invoice_version = readiness.invoice_version
                  AND package.invoice_version = ?
                  AND package.status = 'CURRENT'
            )
            """, Boolean.class, readinessId, invoice.id(),
            invoice.currentVersion(), mustBeEligible, packageId,
            packageVersion, invoice.id(), invoice.currentVersion());
        if (!Boolean.TRUE.equals(valid)) {
            throw new DomainConflictException(
                "EXACT_READINESS_INPUT_REQUIRED",
                "Review must reference the current package and readiness versions.");
        }
    }

    private InputSet currentInputs(UUID invoiceId) {
        return jdbc.query("""
            SELECT current_package_version_id, current_readiness_run_id
            FROM invoices WHERE id = ?
            """, rs -> {
                if (!rs.next()) {
                    return null;
                }
                UUID packageId = rs.getObject(1, UUID.class);
                UUID readinessId = rs.getObject(2, UUID.class);
                return packageId == null || readinessId == null
                    ? null : new InputSet(packageId, readinessId);
            }, invoiceId);
    }

    private ReadinessFailure readinessFailure(UUID resultId, UUID readinessId) {
        return jdbc.query("""
            SELECT result, rule_code FROM invoice_readiness_results
            WHERE id = ? AND readiness_run_id = ?
            """, rs -> rs.next() ? new ReadinessFailure(
                rs.getString(1), rs.getString(2)) : null,
            resultId, readinessId);
    }

    private QueryRow queryRow(UUID queryId, boolean lock) {
        QueryRow row = jdbc.query("""
            SELECT query.id, query.invoice_id, invoice.engagement_month_id,
                   query.owner_subject, query.status
            FROM procurement_queries query
            JOIN invoices invoice ON invoice.id = query.invoice_id
            WHERE query.id = ?
            """ + (lock ? " FOR UPDATE" : ""),
            rs -> rs.next() ? new QueryRow(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5))
                : null, queryId);
        if (row == null) {
            throw new EntityNotFoundException("Procurement query not found.");
        }
        return row;
    }

    private Map<String, Object> queryView(String subject, UUID queryId) {
        Map<String, Object> result = jdbc.query("""
            SELECT query.id, query.status, query.category,
                   query.owner_subject, query.due_at, query.created_at,
                   query.closed_by_subject, query.closed_at, query.close_reason,
                   COUNT(response.id), invoice.engagement_month_id,
                   month.engagement_id
            FROM procurement_queries query
            JOIN invoices invoice ON invoice.id = query.invoice_id
            JOIN engagement_months month
              ON month.id = invoice.engagement_month_id
            LEFT JOIN procurement_query_responses response
              ON response.query_id = query.id
            WHERE query.id = ?
            GROUP BY query.id, invoice.engagement_month_id,
                     month.engagement_id
            """, rs -> {
                if (!rs.next()) {
                    throw new EntityNotFoundException(
                        "Procurement query not found.");
                }
                return map(
                    "queryId", rs.getObject(1, UUID.class),
                    "status", rs.getString(2),
                    "category", rs.getString(3),
                    "ownerDisplay", rs.getString(4),
                    "dueAt", offset(rs.getTimestamp(5)),
                    "createdAt", offset(rs.getTimestamp(6)),
                    "closedByDisplay", rs.getString(7),
                    "closedAt", offset(rs.getTimestamp(8)),
                    "closeReason", rs.getString(9),
                    "responseCount", rs.getLong(10),
                    "monthId", rs.getObject(11, UUID.class),
                    "engagementId", rs.getObject(12, UUID.class));
            }, queryId);
        UUID engagementId = (UUID) result.remove("engagementId");
        boolean mayReadResponses = subject.equals(result.get("ownerDisplay"))
            || hasScopedPermission(subject, engagementId, "procurement.review");
        result.put("responses", mayReadResponses
            ? queryResponses(queryId) : List.of());
        result.put("responsesRestricted", !mayReadResponses);
        return result;
    }

    private List<Map<String, Object>> queryResponses(UUID queryId) {
        return jdbc.query("""
            SELECT id, response_text, responded_by_subject, responded_at
            FROM procurement_query_responses
            WHERE query_id = ?
            ORDER BY responded_at, id
            """, (rs, rowNum) -> map(
                "responseId", rs.getObject(1, UUID.class),
                "response", rs.getString(2),
                "respondedByDisplay", rs.getString(3),
                "recordedAt", offset(rs.getTimestamp(4))), queryId);
    }

    private UUID createExceptionReadinessRun(
        InvoiceRow invoice,
        UUID priorRunId,
        UUID resultId,
        UUID exceptionId,
        String subject
    ) {
        Map<String, Object> prior = jdbc.query("""
            SELECT input_manifest::text, policy_version,
                   package_version_id, handoff_id
            FROM invoice_readiness_runs
            WHERE id = ? AND invoice_id = ? AND current_result
            FOR UPDATE
            """, rs -> {
                if (!rs.next()) {
                    return null;
                }
                return map(
                    "manifest", canonical.readMap(rs.getString(1)),
                    "policyVersion", rs.getString(2),
                    "packageId", rs.getObject(3, UUID.class),
                    "handoffId", rs.getObject(4, UUID.class));
            }, priorRunId, invoice.id());
        if (prior == null) {
            throw new DomainConflictException(
                "CURRENT_READINESS_REQUIRED",
                "The exception target is no longer the current readiness run.");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = new LinkedHashMap<>(
            (Map<String, Object>) prior.get("manifest"));
        manifest.put("acceptedExceptionId", exceptionId);
        manifest.put("acceptedReadinessResultId", resultId);
        String inputHash = canonical.sha256(manifest);
        UUID runId = UUID.randomUUID();
        jdbc.update("""
            UPDATE invoice_readiness_runs
            SET current_result = FALSE, eligible = FALSE,
                invalidated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """, priorRunId);
        jdbc.update("""
            INSERT INTO invoice_readiness_runs(
                id, invoice_id, invoice_version, package_version_id,
                handoff_id, input_manifest, input_hash, policy_version,
                overall_status, eligible, current_result,
                evaluated_by_subject, correlation_id
            ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?,
                      'EXCEPTION_ACCEPTED_BY_PROCUREMENT', TRUE, TRUE, ?, ?)
            """, runId, invoice.id(), invoice.currentVersion(),
            prior.get("packageId"), prior.get("handoffId"),
            canonical.write(manifest), inputHash, prior.get("policyVersion"),
            subject, journal.correlationId());
        jdbc.update("""
            INSERT INTO invoice_readiness_results(
                id, readiness_run_id, rule_code, result, severity,
                owner_label, source_object_type, source_object_id,
                source_version, source_hash, freshness_at, remediation_cta)
            SELECT gen_random_uuid(), ?, rule_code,
                   CASE WHEN id = ?
                        THEN 'EXCEPTION_ACCEPTED_BY_PROCUREMENT'
                        ELSE result END,
                   CASE WHEN id = ? THEN 'WARNING' ELSE severity END,
                   owner_label, source_object_type, source_object_id,
                   source_version, source_hash, freshness_at,
                   CASE WHEN id = ? THEN NULL ELSE remediation_cta END
            FROM invoice_readiness_results
            WHERE readiness_run_id = ?
            ORDER BY rule_code
            """, runId, resultId, resultId, resultId, priorRunId);
        return runId;
    }

    private boolean activeScopedOwner(String subject, UUID engagementId) {
        return hasScopedPermission(subject, engagementId, "finance.read");
    }

    private boolean hasScopedPermission(
        String subject,
        UUID engagementId,
        String permission
    ) {
        try {
            authorization.requireEngagement(subject, engagementId, permission);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private long transitionInvoice(
        InvoiceRow invoice,
        String state,
        long expectedVersion
    ) {
        int updated = jdbc.update("""
            UPDATE invoices
            SET status = ?, optimistic_version = optimistic_version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ? AND optimistic_version = ?
            """, state, invoice.id(), expectedVersion);
        if (updated != 1) {
            throw new DomainConflictException(
                "VERSION_MISMATCH", "Invoice was changed concurrently.");
        }
        return expectedVersion + 1;
    }

    private List<Map<String, Object>> paymentRows(UUID invoiceId) {
        return jdbc.query("""
            SELECT id, sequence_number, status, sanitized_comment,
                   external_reference, status_at, expected_payment_date,
                   actual_payment_date, source, recorded_at
            FROM payment_status_history
            WHERE invoice_id = ?
            ORDER BY sequence_number
            """, (rs, rowNum) -> map(
                "paymentId", rs.getObject(1, UUID.class),
                "sequenceNumber", rs.getInt(2),
                "status", rs.getString(3),
                "comment", rs.getString(4),
                "externalReference", rs.getString(5),
                "statusAt", offset(rs.getTimestamp(6)),
                "expectedPaymentDate", rs.getObject(7, LocalDate.class),
                "actualPaymentDate", rs.getObject(8, LocalDate.class),
                "source", rs.getString(9),
                "recordedAt", offset(rs.getTimestamp(10))), invoiceId);
    }

    private void requirePaymentTransition(String prior, String next) {
        Map<String, Set<String>> transitions = Map.of(
            "NOT_SUBMITTED", Set.of("SUBMITTED_TO_AP", "ON_HOLD"),
            "SUBMITTED_TO_AP", Set.of("VALIDATION_IN_PROGRESS", "ON_HOLD"),
            "VALIDATION_IN_PROGRESS",
                Set.of("PAYMENT_SCHEDULED", "PAYMENT_FAILED", "ON_HOLD"),
            "PAYMENT_SCHEDULED",
                Set.of("PAYMENT_INITIATED", "PAYMENT_FAILED", "ON_HOLD"),
            "PAYMENT_INITIATED", Set.of("PAID", "PAYMENT_FAILED", "ON_HOLD"),
            "PAYMENT_FAILED", Set.of("PAYMENT_SCHEDULED", "ON_HOLD"),
            "ON_HOLD", Set.of("VALIDATION_IN_PROGRESS", "PAYMENT_SCHEDULED"));
        String effectivePrior = prior == null ? "NOT_SUBMITTED" : prior;
        if (!transitions.getOrDefault(effectivePrior, Set.of()).contains(next)) {
            throw new DomainConflictException(
                "INVALID_PAYMENT_TRANSITION",
                "The requested payment transition is not allowed.");
        }
    }

    private void requirePaymentDates(PaymentUpdateInput request) {
        if ("PAID".equals(request.status()) && request.actualPaymentDate() == null) {
            throw new IllegalArgumentException(
                "An actual payment date is required for PAID.");
        }
        if (request.statusAt().isAfter(OffsetDateTime.now(clock).plusMinutes(5))) {
            throw new IllegalArgumentException(
                "Payment status time cannot be in the future.");
        }
    }

    private String cleanVisibleComment(String value) {
        String cleaned = clean(value, 500);
        if (cleaned == null) {
            return null;
        }
        return cleaned
            .replaceAll("(?i)(password|secret|token)\\s*[:=]\\s*\\S+", "$1=[REDACTED]")
            .replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "");
    }

    private String clean(String value, int limit) {
        if (value == null) {
            return null;
        }
        String cleaned = value.strip();
        return cleaned.length() <= limit ? cleaned : cleaned.substring(0, limit);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private Map<String, Object> authority(
        FinanceAuthorizationService.Scope scope,
        String permission
    ) {
        return map(
            "permission", permission,
            "engagementId", scope.engagementId(),
            "vendorOrganizationId", scope.vendorOrganizationId(),
            "clientOrganizationId", scope.clientOrganizationId(),
            "procurementOrganizationId", scope.procurementOrganizationId(),
            "financeOrganizationId", scope.financeOrganizationId());
    }

    private Map<String, Object> exceptionAuthority(
        FinanceAuthorizationService.Scope scope,
        String subject,
        FinancePolicyService.Policy policy,
        String action
    ) {
        Map<String, Object> snapshot =
            new LinkedHashMap<>(authority(scope, "procurement.exception"));
        snapshot.put("actorSubject", subject);
        snapshot.put("action", action);
        snapshot.put("policyVersionId", policy.id());
        snapshot.put("policyVersion", policy.version());
        snapshot.put("capturedAt", OffsetDateTime.now(clock));
        return snapshot;
    }

    private Map<String, Object> exportAuthority(
        FinanceAuthorizationService.Scope scope,
        String permission,
        String subject,
        UUID organizationId
    ) {
        Map<String, Object> snapshot =
            new LinkedHashMap<>(authority(scope, permission));
        snapshot.put("actorSubject", subject);
        snapshot.put("actorOrganizationId", organizationId);
        snapshot.put("capturedAt", OffsetDateTime.now(clock));
        return snapshot;
    }

    private Map<String, Object> reference(String type, UUID id) {
        return map("type", type, "id", id);
    }

    private List<Map<String, Object>> metricDefinitions() {
        return jdbc.query("""
            SELECT metric_code, version, display_name, definition,
                   source_label, timezone_semantics, freshness_semantics,
                   empty_semantics
            FROM f05_metric_dictionary
            ORDER BY metric_code, version DESC
            """, (rs, rowNum) -> map(
                "metricCode", rs.getString(1),
                "version", rs.getInt(2),
                "displayName", rs.getString(3),
                "definition", rs.getString(4),
                "sourceLabel", rs.getString(5),
                "timezoneSemantics", rs.getString(6),
                "freshnessSemantics", rs.getString(7),
                "emptySemantics", rs.getString(8)));
    }

    private Map<String, Object> metric(
        List<Map<String, Object>> definitions,
        String code,
        long value,
        boolean available
    ) {
        Map<String, Object> definition = definitions.stream()
            .filter(item -> code.equals(item.get("metricCode")))
            .findFirst()
            .orElse(map("metricCode", code, "displayName", code));
        Map<String, Object> result = new LinkedHashMap<>(definition);
        result.put("metricCode", code);
        result.put("displayName",
            definition.getOrDefault("displayName", code));
        result.put("value", available ? value : null);
        result.put("availability", available ? "AVAILABLE" : "UNAVAILABLE");
        result.put("version", definition.getOrDefault("version", 1));
        result.put("policyVersion", "f05-policy-v1");
        result.put("sourceLabel",
            definition.getOrDefault("sourceLabel", "F05 governed facts"));
        result.put("freshness", "CURRENT");
        result.put("temporalMode", "LIVE");
        result.put("refreshedAt", OffsetDateTime.now(clock));
        return result;
    }

    private Map<String, Object> queue(
        String code,
        String label,
        long count,
        String path
    ) {
        return map("key", code, "label", label, "count", count,
            "actionPath", path);
    }

    private List<Map<String, Object>> reportDefinitions() {
        return List.of(
            report("ATTENDANCE_COMPLIANCE", "Attendance compliance",
                "Scoped closed attendance and exception evidence."),
            report("PLAN_TIMELINESS", "Plan timeliness",
                "Frozen baseline and commitment timing."),
            report("DELIVERY_ACCEPTANCE", "Delivery acceptance",
                "Certified item and monthly summary outcomes."),
            report("CONFIRMATION_COMPLETION", "Confirmation completion",
                "Verified confirmation distinct from downstream exception."),
            report("EVIDENCE_PACKAGE_VERSIONS", "Evidence package versions",
                "Immutable package lineage, hashes and supersession."),
            report("INVOICE_READINESS", "Invoice readiness",
                "Nine-pillar readiness with explicit blockers and exceptions."),
            report("PROCUREMENT_AGING", "Procurement aging",
                "Review, query, hold and change-request aging."),
            report("PAYMENT_AGING", "Payment aging",
                "Sanitized append-only AP/ERP status aging."),
            report("EXCEPTION_REOPEN", "Exception and reopen",
                "Authority-bound exceptions and invalidation lineage."),
            report("COMMUNICATION_AUDIT", "Communication and audit",
                "Scoped workflow event and audit evidence."));
    }

    private Map<String, Object> report(
        String id,
        String name,
        String description
    ) {
        return map(
            "reportId", id,
            "name", name,
            "version", "v1",
            "description", description,
            "availableFormats", List.of("CSV", "XLSX", "PDF", "JSON"),
            "snapshotMode", "SELECTABLE");
    }

    private ReportDefinition reportDefinition(String id) {
        return reportDefinitions().stream()
            .filter(value -> id.equals(value.get("reportId")))
            .findFirst()
            .map(value -> new ReportDefinition(
                id, "v1", Set.of("CSV", "XLSX", "PDF", "JSON")))
            .orElse(null);
    }

    private String reportPermission(String reportId) {
        return switch (reportId) {
            case "PROCUREMENT_AGING" -> "procurement.review";
            case "PAYMENT_AGING" -> "payment.update";
            case "EXCEPTION_REOPEN" -> "procurement.exception";
            case "COMMUNICATION_AUDIT" -> "finance.audit.read";
            default -> "finance.read";
        };
    }

    private UUID requestedEngagement(
        Map<String, Object> filters,
        List<UUID> authorized
    ) {
        Object raw = filters == null ? null : filters.get("engagementId");
        UUID requested;
        try {
            requested = raw == null ? null : UUID.fromString(raw.toString());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid engagement filter.", exception);
        }
        if (requested == null && authorized.size() == 1) {
            return authorized.getFirst();
        }
        if (requested == null || !authorized.contains(requested)) {
            throw new EntityNotFoundException("Finance scope not found.");
        }
        return requested;
    }

    private UUID actorOrganization(
        String subject,
        UUID engagementId,
        FinanceAuthorizationService.Scope scope
    ) {
        UUID value = jdbc.query("""
            SELECT membership.organization_id
            FROM user_profiles profile
            JOIN memberships membership
              ON membership.user_profile_id = profile.id
            WHERE profile.identity_subject = ?
              AND membership.status = 'ACTIVE'
              AND membership.organization_id IN (?, ?, ?, ?)
            ORDER BY membership.organization_id
            LIMIT 1
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
            subject, scope.vendorOrganizationId(), scope.clientOrganizationId(),
            scope.procurementOrganizationId(), scope.financeOrganizationId());
        if (value == null) {
            throw new EntityNotFoundException("Finance scope not found.");
        }
        return value;
    }

    private Map<String, Object> exportMap(
        UUID id,
        UUID organizationId,
        UUID engagementId,
        String reportCode,
        String reportVersion,
        String format,
        String filters,
        String status,
        int progress,
        Long rowCount,
        String resultHash,
        OffsetDateTime freshnessAt,
        String snapshotLabel,
        OffsetDateTime requestedAt,
        OffsetDateTime completedAt,
        OffsetDateTime expiresAt,
        UUID correlationId
    ) {
        String jobStatus = switch (status) {
            case "PENDING" -> "QUEUED";
            case "CLAIMED" -> "RUNNING";
            case "FAILED" -> "RETRY_SCHEDULED";
            default -> status;
        };
        return map(
            "exportId", id,
            "organizationId", organizationId,
            "engagementId", engagementId,
            "reportId", reportCode,
            "reportVersion", reportVersion,
            "format", format,
            "filters", filters,
            "reportName", reportCode.replace('_', ' '),
            "status", jobStatus,
            "progressPercent", progress,
            "rowCount", rowCount,
            "sha256", resultHash,
            "sourceFreshness", freshnessAt == null ? "UNKNOWN" : "CURRENT",
            "generatedAt", completedAt,
            "filterSummary", filters,
            "temporalMode", snapshotLabel,
            "requestedAt", requestedAt,
            "completedAt", completedAt,
            "expiresAt", expiresAt,
            "correlationId", correlationId,
            "providerStatus", storage.configurationStatus(),
            "downloadAllowed", "READY".equals(status));
    }

    private static OffsetDateTime offset(Timestamp value) {
        return value == null ? null : value.toInstant().atOffset(ZoneOffset.UTC);
    }

    private Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }

    private record InvoiceRow(
        UUID id,
        UUID monthId,
        UUID engagementId,
        int currentVersion,
        long optimisticVersion,
        String status,
        String createdBySubject
    ) {
    }

    private record DashboardCounts(
        long ready,
        long readinessEvaluated,
        long confirmed,
        long confirmationRecorded,
        long payment,
        long review,
        long reopened
    ) {
    }

    private record InputSet(UUID packageId, UUID readinessId) {
    }

    private record QueryRow(
        UUID id,
        UUID invoiceId,
        UUID monthId,
        String ownerSubject,
        String status
    ) {
    }

    private record ReadinessFailure(String result, String ruleCode) {
    }

    private record ExceptionApprovalRow(
        UUID invoiceId,
        UUID resultId,
        UUID readinessRunId,
        UUID packageId,
        int packageVersion,
        UUID policyId,
        int policyVersion,
        String status,
        OffsetDateTime validUntil,
        String requestedBy,
        UUID monthId,
        UUID engagementId,
        int invoiceVersion,
        long invoiceOptimisticVersion,
        String invoiceState,
        String invoiceCreator
    ) {
    }

    private record ReportDefinition(
        String id,
        String version,
        Set<String> formats
    ) {
    }

    private record ExportHeader(
        UUID engagementId,
        String reportCode
    ) {
    }

    private record ExportAccess(
        ExportHeader header,
        FinanceAuthorizationService.Scope scope
    ) {
    }

    private record ExportDownload(
        UUID engagementId,
        String status,
        UUID artifactId,
        String resultHash,
        String scanStatus,
        String mediaType,
        String safeName,
        OffsetDateTime expiresAt
    ) {
    }

    public record ExportDownloadResult(
        byte[] content,
        String mediaType,
        String safeName
    ) {
    }
}
