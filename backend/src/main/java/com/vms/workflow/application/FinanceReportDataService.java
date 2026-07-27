package com.vms.workflow.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Builds the governed, report-specific data set for an F05 export.
 *
 * <p>The asynchronous worker does not inherit a request security context.
 * Consequently, every export is evaluated against the immutable authority
 * snapshot captured when it was requested, and every query is constrained to
 * the snapshot engagement and optional month.</p>
 */
@Component
public class FinanceReportDataService {
    private static final Set<String> REPORTS = Set.of(
        "ATTENDANCE_COMPLIANCE",
        "PLAN_TIMELINESS",
        "DELIVERY_ACCEPTANCE",
        "CONFIRMATION_COMPLETION",
        "EVIDENCE_PACKAGE_VERSIONS",
        "INVOICE_READINESS",
        "PROCUREMENT_AGING",
        "PAYMENT_AGING",
        "EXCEPTION_REOPEN",
        "COMMUNICATION_AUDIT");

    private final JdbcTemplate jdbc;
    private final FinanceCanonicalJson canonical;

    public FinanceReportDataService(
        JdbcTemplate jdbc,
        FinanceCanonicalJson canonical
    ) {
        this.jdbc = jdbc;
        this.canonical = canonical;
    }

    public List<Map<String, Object>> rows(
        String reportCode,
        UUID engagementId,
        UUID monthId,
        UUID actorOrganizationId,
        String requestedBy,
        Map<String, Object> authoritySnapshot
    ) {
        if (!REPORTS.contains(reportCode)) {
            throw new IllegalArgumentException("EXPORT_REPORT_UNSUPPORTED");
        }
        validateAuthority(
            reportCode, engagementId, actorOrganizationId, requestedBy,
            authoritySnapshot);
        return switch (reportCode) {
            case "ATTENDANCE_COMPLIANCE" ->
                scoped(attendanceComplianceSql(), engagementId, monthId);
            case "PLAN_TIMELINESS" ->
                scoped(planTimelinessSql(), engagementId, monthId);
            case "DELIVERY_ACCEPTANCE" ->
                scoped(deliveryAcceptanceSql(), engagementId, monthId);
            case "CONFIRMATION_COMPLETION" ->
                scoped(confirmationCompletionSql(), engagementId, monthId);
            case "EVIDENCE_PACKAGE_VERSIONS" ->
                scoped(evidencePackageVersionsSql(), engagementId, monthId);
            case "INVOICE_READINESS" ->
                scoped(invoiceReadinessSql(), engagementId, monthId);
            case "PROCUREMENT_AGING" ->
                scoped(procurementAgingSql(), engagementId, monthId);
            case "PAYMENT_AGING" ->
                scoped(paymentAgingSql(), engagementId, monthId);
            case "EXCEPTION_REOPEN" ->
                scoped(exceptionReopenSql(), engagementId, monthId);
            case "COMMUNICATION_AUDIT" ->
                scoped(communicationAuditSql(), engagementId, monthId);
            default ->
                throw new IllegalArgumentException("EXPORT_REPORT_UNSUPPORTED");
        };
    }

    private void validateAuthority(
        String reportCode,
        UUID engagementId,
        UUID actorOrganizationId,
        String requestedBy,
        Map<String, Object> snapshot
    ) {
        if (snapshot == null
            || !requiredPermission(reportCode).equals(
                text(snapshot.get("permission")))
            || !engagementId.toString().equals(
                text(snapshot.get("engagementId")))
            || !requestedBy.equals(text(snapshot.get("actorSubject")))
            || !actorOrganizationId.toString().equals(
                text(snapshot.get("actorOrganizationId")))
            || snapshot.get("capturedAt") == null) {
            throw new SecurityException("EXPORT_AUTHORITY_SNAPSHOT_INVALID");
        }

        ScopeOrganizations current = jdbc.query("""
            SELECT vendor_organization_id, client_organization_id,
                   procurement_organization_id, finance_organization_id
            FROM engagements
            WHERE id = ?
            """, rs -> rs.next() ? new ScopeOrganizations(
                rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class),
                rs.getObject(4, UUID.class)) : null, engagementId);
        if (current == null
            || !same(snapshot, "vendorOrganizationId", current.vendor())
            || !same(snapshot, "clientOrganizationId", current.client())
            || !same(snapshot, "procurementOrganizationId",
                current.procurement())
            || !same(snapshot, "financeOrganizationId", current.finance())
            || !current.contains(actorOrganizationId)) {
            throw new SecurityException("EXPORT_AUTHORITY_SCOPE_INVALID");
        }
    }

    private boolean same(
        Map<String, Object> snapshot,
        String key,
        UUID expected
    ) {
        return expected == null
            ? snapshot.get(key) == null
            : expected.toString().equals(text(snapshot.get(key)));
    }

    private String requiredPermission(String reportCode) {
        return switch (reportCode) {
            case "PROCUREMENT_AGING" -> "procurement.review";
            case "PAYMENT_AGING" -> "payment.update";
            case "EXCEPTION_REOPEN" -> "procurement.exception";
            case "COMMUNICATION_AUDIT" -> "finance.audit.read";
            default -> "finance.read";
        };
    }

    private List<Map<String, Object>> scoped(
        String sql,
        UUID engagementId,
        UUID monthId
    ) {
        return jdbc.query(sql,
            (rs, index) -> canonical.readMap(rs.getString(1)),
            engagementId, monthId, monthId);
    }

    private String attendanceComplianceSql() {
        return """
            SELECT to_jsonb(report_row)::text
            FROM (
                SELECT month.id AS "monthId",
                       month.month_start_date AS "monthStartDate",
                       snapshot.id AS "snapshotId",
                       snapshot.version AS "snapshotVersion",
                       snapshot.checksum AS "snapshotHash",
                       snapshot.closed_at AS "closedAt",
                       day.employee_id AS "employeeId",
                       day.work_date AS "workDate",
                       day.final_status AS "finalStatus",
                       day.net_minutes AS "netMinutes",
                       day.source_mode AS "sourceMode"
                FROM attendance_snapshot_versions snapshot
                JOIN engagement_months month
                  ON month.id = snapshot.engagement_month_id
                LEFT JOIN attendance_snapshot_days day
                  ON day.snapshot_id = snapshot.id
                WHERE month.engagement_id = ?
                  AND (?::uuid IS NULL OR month.id = ?::uuid)
                ORDER BY month.month_start_date DESC, snapshot.version DESC,
                         day.work_date, day.employee_id
            ) report_row
            """;
    }

    private String planTimelinessSql() {
        return """
            SELECT to_jsonb(report_row)::text
            FROM (
                SELECT month.id AS "monthId",
                       month.month_start_date AS "monthStartDate",
                       version.id AS "planVersionId",
                       version.version AS "planVersion",
                       version.state AS "planState",
                       version.baseline_type AS "baselineType",
                       version.created_at AS "createdAt",
                       version.submitted_at AS "submittedAt",
                       version.frozen_at AS "frozenAt",
                       baseline.id AS "baselineId",
                       baseline.checksum AS "baselineHash",
                       baseline.deliverable_count AS "deliverableCount",
                       CASE WHEN version.submitted_at IS NULL THEN NULL
                            ELSE version.submitted_at::date
                                 - month.month_start_date
                       END AS "submissionDayOffset"
                FROM delivery_plans plan
                JOIN engagement_months month
                  ON month.id = plan.engagement_month_id
                JOIN delivery_plan_versions version
                  ON version.plan_id = plan.id
                LEFT JOIN delivery_plan_baselines baseline
                  ON baseline.plan_version_id = version.id
                WHERE month.engagement_id = ?
                  AND (?::uuid IS NULL OR month.id = ?::uuid)
                ORDER BY month.month_start_date DESC, version.version DESC
            ) report_row
            """;
    }

    private String deliveryAcceptanceSql() {
        return """
            SELECT to_jsonb(report_row)::text
            FROM (
                SELECT month.id AS "monthId",
                       month.month_start_date AS "monthStartDate",
                       submission.id AS "submissionId",
                       submission.version AS "submissionVersion",
                       submission.status AS "submissionStatus",
                       submission.checksum AS "submissionHash",
                       outcome.deliverable_version_id AS "deliverableVersionId",
                       outcome.declared_outcome AS "declaredOutcome",
                       outcome.completion_percent AS "completionPercent",
                       certification.decision AS "acceptanceDecision",
                       certification.action_hash AS "acceptanceHash",
                       certification.decided_at AS "decidedAt"
                FROM delivery_submissions submission
                JOIN engagement_months month
                  ON month.id = submission.engagement_month_id
                LEFT JOIN deliverable_delivery_outcomes outcome
                  ON outcome.submission_id = submission.id
                LEFT JOIN deliverable_certifications certification
                  ON certification.submission_id = submission.id
                 AND certification.deliverable_version_id =
                     outcome.deliverable_version_id
                WHERE month.engagement_id = ?
                  AND (?::uuid IS NULL OR month.id = ?::uuid)
                ORDER BY month.month_start_date DESC, submission.version DESC,
                         outcome.deliverable_version_id
            ) report_row
            """;
    }

    private String confirmationCompletionSql() {
        return """
            SELECT to_jsonb(report_row)::text
            FROM (
                SELECT month.id AS "monthId",
                       month.month_start_date AS "monthStartDate",
                       request.id AS "confirmationRequestId",
                       request.version AS "requestVersion",
                       request.status AS "requestStatus",
                       request.transport_status AS "transportStatus",
                       request.quorum_mode AS "quorumMode",
                       request.quorum_required AS "quorumRequired",
                       request.requested_at AS "requestedAt",
                       request.due_at AS "dueAt",
                       request.completed_at AS "completedAt",
                       COALESCE(actions.action_count, 0) AS "actionCount",
                       COALESCE(actions.verified_count, 0) AS "verifiedCount",
                       COALESCE(actions.confirmation_count, 0)
                           AS "confirmationCount"
                FROM business_confirmation_requests request
                JOIN engagement_months month
                  ON month.id = request.engagement_month_id
                LEFT JOIN LATERAL (
                    SELECT COUNT(*) AS action_count,
                           COUNT(*) FILTER (
                               WHERE action.verification_status = 'VERIFIED')
                               AS verified_count,
                           COUNT(*) FILTER (WHERE action.action = 'CONFIRM')
                               AS confirmation_count
                    FROM business_confirmation_actions action
                    WHERE action.request_id = request.id
                ) actions ON TRUE
                WHERE month.engagement_id = ?
                  AND (?::uuid IS NULL OR month.id = ?::uuid)
                ORDER BY month.month_start_date DESC, request.version DESC
            ) report_row
            """;
    }

    private String evidencePackageVersionsSql() {
        return """
            SELECT to_jsonb(report_row)::text
            FROM (
                SELECT month.id AS "monthId",
                       month.month_start_date AS "monthStartDate",
                       package.id AS "packageVersionId",
                       package.version AS "packageVersion",
                       package.status AS "packageStatus",
                       package.canonical_input_hash AS "inputHash",
                       package.hash_algorithm AS "hashAlgorithm",
                       package.hash_schema_version AS "hashSchemaVersion",
                       package.render_version AS "renderVersion",
                       package.supersedes_id AS "supersedesId",
                       package.invalidation_reason AS "invalidationReason",
                       package.generated_at AS "generatedAt"
                FROM evidence_package_versions package
                JOIN engagement_months month
                  ON month.id = package.engagement_month_id
                WHERE month.engagement_id = ?
                  AND (?::uuid IS NULL OR month.id = ?::uuid)
                ORDER BY month.month_start_date DESC, package.version DESC
            ) report_row
            """;
    }

    private String invoiceReadinessSql() {
        return """
            SELECT to_jsonb(report_row)::text
            FROM (
                SELECT month.id AS "monthId",
                       month.month_start_date AS "monthStartDate",
                       invoice.id AS "invoiceId",
                       invoice.invoice_number AS "invoiceNumber",
                       invoice.current_version AS "invoiceVersion",
                       invoice.status AS "invoiceStatus",
                       readiness.id AS "readinessRunId",
                       readiness.policy_version AS "policyVersion",
                       readiness.overall_status AS "overallStatus",
                       readiness.eligible AS "eligible",
                       readiness.current_result AS "currentResult",
                       readiness.input_hash AS "inputHash",
                       readiness.evaluated_at AS "evaluatedAt",
                       result.rule_code AS "ruleCode",
                       result.result AS "ruleResult",
                       result.severity AS "severity",
                       result.owner_label AS "ownerLabel",
                       result.source_object_type AS "sourceObjectType",
                       result.source_object_id AS "sourceObjectId",
                       result.source_version AS "sourceVersion",
                       result.source_hash AS "sourceHash",
                       result.freshness_at AS "freshnessAt"
                FROM invoices invoice
                JOIN engagement_months month
                  ON month.id = invoice.engagement_month_id
                LEFT JOIN invoice_readiness_runs readiness
                  ON readiness.id = invoice.current_readiness_run_id
                LEFT JOIN invoice_readiness_results result
                  ON result.readiness_run_id = readiness.id
                WHERE month.engagement_id = ?
                  AND (?::uuid IS NULL OR month.id = ?::uuid)
                ORDER BY month.month_start_date DESC, invoice.updated_at DESC,
                         result.rule_code
            ) report_row
            """;
    }

    private String procurementAgingSql() {
        return """
            SELECT to_jsonb(report_row)::text
            FROM (
                SELECT month.id AS "monthId",
                       month.month_start_date AS "monthStartDate",
                       invoice.id AS "invoiceId",
                       invoice.invoice_number AS "invoiceNumber",
                       invoice.status AS "invoiceStatus",
                       review.id AS "reviewId",
                       review.decision AS "reviewDecision",
                       review.category AS "reviewCategory",
                       review.reviewed_at AS "reviewedAt",
                       query.id AS "queryId",
                       query.category AS "queryCategory",
                       query.status AS "queryStatus",
                       query.created_at AS "queryCreatedAt",
                       query.due_at AS "queryDueAt",
                       query.closed_at AS "queryClosedAt",
                       EXTRACT(EPOCH FROM (
                           COALESCE(query.closed_at, CURRENT_TIMESTAMP)
                           - query.created_at))::bigint AS "queryAgeSeconds",
                       COALESCE(response.response_count, 0)
                           AS "responseCount",
                       response.last_response_at AS "lastResponseAt"
                FROM invoices invoice
                JOIN engagement_months month
                  ON month.id = invoice.engagement_month_id
                JOIN procurement_reviews review
                  ON review.invoice_id = invoice.id
                LEFT JOIN procurement_queries query
                  ON query.review_id = review.id
                LEFT JOIN LATERAL (
                    SELECT COUNT(*) AS response_count,
                           MAX(value.responded_at) AS last_response_at
                    FROM procurement_query_responses value
                    WHERE value.query_id = query.id
                ) response ON TRUE
                WHERE month.engagement_id = ?
                  AND (?::uuid IS NULL OR month.id = ?::uuid)
                ORDER BY month.month_start_date DESC, review.reviewed_at DESC,
                         query.created_at DESC
            ) report_row
            """;
    }

    private String paymentAgingSql() {
        return """
            SELECT to_jsonb(report_row)::text
            FROM (
                SELECT month.id AS "monthId",
                       month.month_start_date AS "monthStartDate",
                       invoice.id AS "invoiceId",
                       invoice.invoice_number AS "invoiceNumber",
                       invoice.status AS "invoiceStatus",
                       payment.sequence_number AS "sequenceNumber",
                       payment.status AS "paymentStatus",
                       payment.sanitized_comment AS "sanitizedComment",
                       payment.external_reference AS "externalReference",
                       payment.status_at AS "statusAt",
                       payment.expected_payment_date AS "expectedPaymentDate",
                       payment.actual_payment_date AS "actualPaymentDate",
                       payment.source AS "paymentSource",
                       EXTRACT(EPOCH FROM (
                           CURRENT_TIMESTAMP - payment.status_at))::bigint
                           AS "statusAgeSeconds"
                FROM invoices invoice
                JOIN engagement_months month
                  ON month.id = invoice.engagement_month_id
                JOIN payment_status_history payment
                  ON payment.invoice_id = invoice.id
                WHERE month.engagement_id = ?
                  AND (?::uuid IS NULL OR month.id = ?::uuid)
                ORDER BY month.month_start_date DESC, invoice.id,
                         payment.sequence_number DESC
            ) report_row
            """;
    }

    private String exceptionReopenSql() {
        return """
            SELECT to_jsonb(report_row)::text
            FROM (
                WITH scoped_month AS (
                    SELECT id, month_start_date
                    FROM engagement_months
                    WHERE engagement_id = ?
                      AND (?::uuid IS NULL OR id = ?::uuid)
                )
                SELECT month.id AS "monthId",
                       month.month_start_date AS "monthStartDate",
                       'PROCUREMENT_EXCEPTION'::text AS "recordType",
                       exception.id::text AS "recordId",
                       invoice.id AS "invoiceId",
                       result.rule_code AS "ruleCode",
                       result.result AS "ruleResult",
                       exception.valid_until AS "validUntil",
                       COALESCE(exception.second_approved_at,
                                exception.requested_at) AS "recordedAt",
                       NULL::text AS "reasonCode"
                FROM procurement_exceptions exception
                JOIN procurement_reviews review
                  ON review.id = exception.review_id
                JOIN invoices invoice ON invoice.id = review.invoice_id
                JOIN scoped_month month
                  ON month.id = invoice.engagement_month_id
                JOIN invoice_readiness_results result
                  ON result.id = exception.readiness_result_id
                UNION ALL
                SELECT month.id AS "monthId",
                       month.month_start_date AS "monthStartDate",
                       'HANDOFF_INVALIDATION'::text AS "recordType",
                       invalidation.id::text AS "recordId",
                       NULL::uuid AS "invoiceId",
                       NULL::text AS "ruleCode",
                       NULL::text AS "ruleResult",
                       NULL::timestamptz AS "validUntil",
                       invalidation.invalidated_at AS "recordedAt",
                       invalidation.reason_code AS "reasonCode"
                FROM f05_handoff_invalidations invalidation
                JOIN f05_certification_handoffs handoff
                  ON handoff.id = invalidation.handoff_id
                JOIN scoped_month month
                  ON month.id = handoff.engagement_month_id
                ORDER BY "monthStartDate" DESC, "recordedAt" DESC, "recordId"
            ) report_row
            """;
    }

    private String communicationAuditSql() {
        return """
            SELECT to_jsonb(report_row)::text
            FROM (
                WITH scoped_month AS (
                    SELECT id, month_start_date
                    FROM engagement_months
                    WHERE engagement_id = ?
                      AND (?::uuid IS NULL OR id = ?::uuid)
                )
                SELECT month.id AS "monthId",
                       month.month_start_date AS "monthStartDate",
                       'AUDIT'::text AS "recordType",
                       audit.id::text AS "recordId",
                       audit.action AS "eventType",
                       audit.object_type AS "objectType",
                       audit.object_id::text AS "objectId",
                       audit.object_version AS "objectVersion",
                       audit.result AS "result",
                       audit.reason_code AS "reasonCode",
                       audit.correlation_id AS "correlationId",
                       audit.recorded_at AS "recordedAt"
                FROM f05_audit_events audit
                JOIN scoped_month month
                  ON month.id = audit.engagement_month_id
                UNION ALL
                SELECT month.id AS "monthId",
                       month.month_start_date AS "monthStartDate",
                       'DOMAIN_EVENT'::text AS "recordType",
                       event.id::text AS "recordId",
                       event.event_type AS "eventType",
                       event.aggregate_type AS "objectType",
                       event.aggregate_id::text AS "objectId",
                       event.aggregate_version AS "objectVersion",
                       'RECORDED'::text AS "result",
                       NULL::text AS "reasonCode",
                       event.correlation_id AS "correlationId",
                       event.recorded_at AS "recordedAt"
                FROM f05_domain_events event
                JOIN scoped_month month
                  ON month.id = event.engagement_month_id
                ORDER BY "monthStartDate" DESC, "recordedAt" DESC, "recordId"
            ) report_row
            """;
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private record ScopeOrganizations(
        UUID vendor,
        UUID client,
        UUID procurement,
        UUID finance
    ) {
        private boolean contains(UUID organizationId) {
            return organizationId.equals(vendor)
                || organizationId.equals(client)
                || organizationId.equals(procurement)
                || organizationId.equals(finance);
        }
    }
}
