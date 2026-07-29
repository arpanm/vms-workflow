package com.vms.workflow.api;

import com.vms.workflow.api.CertificationDtos.CertificationRequest;
import com.vms.workflow.api.CertificationDtos.CertificationInboxView;
import com.vms.workflow.api.CertificationDtos.CertificationOperationsView;
import com.vms.workflow.api.CertificationDtos.ClarificationRequest;
import com.vms.workflow.api.CertificationDtos.CloseMonthInput;
import com.vms.workflow.api.CertificationDtos.AttendanceExceptionInput;
import com.vms.workflow.api.CertificationDtos.AttendanceExceptionView;
import com.vms.workflow.api.CertificationDtos.ConfirmationActionRequest;
import com.vms.workflow.api.CertificationDtos.ConfirmationRequestInput;
import com.vms.workflow.api.CertificationDtos.ConfirmationRequestView;
import com.vms.workflow.api.CertificationDtos.GovernanceDecisionInput;
import com.vms.workflow.api.CertificationDtos.GovernanceDecisionView;
import com.vms.workflow.api.CertificationDtos.EvidenceExceptionInput;
import com.vms.workflow.api.CertificationDtos.EvidenceExceptionView;
import com.vms.workflow.api.CertificationDtos.InboundMessageReviewInput;
import com.vms.workflow.api.CertificationDtos.InboundMessageRecordInput;
import com.vms.workflow.api.CertificationDtos.InboundReviewView;
import com.vms.workflow.api.CertificationDtos.InvalidationResolutionInput;
import com.vms.workflow.api.CertificationDtos.InvalidationResolutionView;
import com.vms.workflow.api.CertificationDtos.ManualEvidenceReviewInput;
import com.vms.workflow.api.CertificationDtos.ManualEvidenceRecordInput;
import com.vms.workflow.api.CertificationDtos.MonthCertificationView;
import com.vms.workflow.api.CertificationDtos.MonthClosureView;
import com.vms.workflow.api.CertificationDtos.NotificationReplayInput;
import com.vms.workflow.api.CertificationDtos.NotificationReplayView;
import com.vms.workflow.api.CertificationDtos.PolicyVersionInput;
import com.vms.workflow.api.CertificationDtos.PolicyVersionView;
import com.vms.workflow.api.CertificationDtos.ReadinessView;
import com.vms.workflow.api.CertificationDtos.ReopenDecisionInput;
import com.vms.workflow.api.CertificationDtos.ReopenDecisionView;
import com.vms.workflow.api.CertificationDtos.ReopenRequestInput;
import com.vms.workflow.api.CertificationDtos.SaveSubmissionRequest;
import com.vms.workflow.api.CertificationDtos.SubmitSubmissionRequest;
import com.vms.workflow.api.CertificationDtos.SummaryRequest;
import com.vms.workflow.application.BusinessConfirmationService;
import com.vms.workflow.application.CertificationOperationsService;
import com.vms.workflow.application.CertificationPolicyService;
import com.vms.workflow.application.CertificationReadinessService;
import com.vms.workflow.application.CertificationReviewService;
import com.vms.workflow.application.CertificationWorkflowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/certification")
@ApiResponses({
    @ApiResponse(responseCode = "400", description = "Invalid or incomplete input"),
    @ApiResponse(responseCode = "401", description = "Authentication required"),
    @ApiResponse(responseCode = "404",
        description = "Resource absent or outside the authenticated scope"),
    @ApiResponse(responseCode = "409",
        description = "Typed state, version or idempotency conflict")
})
public class CertificationController {
    private final CertificationWorkflowService workflow;
    private final CertificationReadinessService readiness;
    private final BusinessConfirmationService confirmation;
    private final CertificationReviewService reviews;
    private final CertificationOperationsService operations;
    private final CertificationPolicyService policies;

    public CertificationController(
        CertificationWorkflowService workflow,
        CertificationReadinessService readiness,
        BusinessConfirmationService confirmation,
        CertificationReviewService reviews,
        CertificationOperationsService operations,
        CertificationPolicyService policies
    ) {
        this.workflow = workflow;
        this.readiness = readiness;
        this.confirmation = confirmation;
        this.reviews = reviews;
        this.operations = operations;
        this.policies = policies;
    }

    @GetMapping("/months/{monthId}")
    @Operation(summary =
        "Read the scoped certification workspace and immutable evidence lineage")
    public ResponseEntity<MonthCertificationView> month(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID monthId
    ) {
        return monthResponse(workflow.workspace(jwt.getSubject(), monthId));
    }

    @GetMapping("/inbox")
    @Operation(summary =
        "List server-scoped certification and confirmation work across visible months")
    public ResponseEntity<CertificationInboxView> inbox(
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam(defaultValue = "50") int limit
    ) {
        return ResponseEntity.ok(operations.inbox(jwt.getSubject(), limit));
    }

    @GetMapping("/operations")
    @Operation(summary =
        "Read scoped notification, reminder, expiry and F05 handoff queue health")
    public ResponseEntity<CertificationOperationsView> operations(
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam(defaultValue = "100") int limit
    ) {
        return ResponseEntity.ok(operations.operations(
            jwt.getSubject(), limit));
    }

    @PostMapping("/months/{monthId}/submissions")
    @Operation(summary =
        "Autosave a new versioned vendor submission draft against the frozen baseline")
    @ApiResponse(responseCode = "201", description = "Draft version created")
    public ResponseEntity<MonthCertificationView> saveSubmission(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID monthId,
        @Valid @RequestBody SaveSubmissionRequest request,
        @RequestHeader("If-Match") String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        MonthCertificationView result = workflow.saveSubmission(
            jwt.getSubject(), monthId, request, ifMatch, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED)
            .eTag(String.valueOf(result.version()))
            .body(result);
    }

    @PostMapping("/submissions/{submissionId}/submit")
    @Operation(summary =
        "Validate, hash and atomically lock a complete vendor submission")
    public ResponseEntity<MonthCertificationView> submit(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID submissionId,
        @Valid @RequestBody SubmitSubmissionRequest request,
        @RequestHeader("If-Match") String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return monthResponse(workflow.submit(
            jwt.getSubject(), submissionId, request.expectedSubmissionVersion(),
            ifMatch, idempotencyKey));
    }

    @PostMapping("/submissions/{submissionId}/clarifications")
    @Operation(summary =
        "Append a scoped clarification question or vendor response without rewriting evidence")
    public ResponseEntity<MonthCertificationView> clarify(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID submissionId,
        @Valid @RequestBody ClarificationRequest request,
        @RequestHeader("If-Match") String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return monthResponse(workflow.clarify(
            jwt.getSubject(), submissionId, request, ifMatch, idempotencyKey));
    }

    @PostMapping("/submissions/{submissionId}/certifications")
    @Operation(summary =
        "Record a product-owner item and criterion decision with server-resolved authority")
    public ResponseEntity<MonthCertificationView> certify(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID submissionId,
        @Valid @RequestBody CertificationRequest request,
        @RequestHeader("If-Match") String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return monthResponse(workflow.certify(
            jwt.getSubject(), submissionId, request, ifMatch, idempotencyKey));
    }

    @PostMapping("/months/{monthId}/summaries")
    @Operation(summary =
        "Create a deterministic versioned monthly certification summary after terminal decisions")
    @ApiResponse(responseCode = "201", description = "Summary version created")
    public ResponseEntity<MonthCertificationView> createSummary(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID monthId,
        @Valid @RequestBody SummaryRequest request,
        @RequestHeader("If-Match") String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        MonthCertificationView result = workflow.createSummary(
            jwt.getSubject(), monthId, request, ifMatch, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED)
            .eTag(String.valueOf(result.version()))
            .body(result);
    }

    @GetMapping("/months/{monthId}/readiness")
    @Operation(summary =
        "Evaluate and version the five-pillar server-side readiness manifest")
    public ResponseEntity<ReadinessView> readiness(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID monthId
    ) {
        ReadinessView result = readiness.evaluate(jwt.getSubject(), monthId);
        return ResponseEntity.ok()
            .eTag(String.valueOf(result.version()))
            .body(result);
    }

    @PostMapping("/months/{monthId}/confirmation-requests")
    @Operation(summary =
        "Create an exact-version confirmation request after readiness passes")
    @ApiResponse(responseCode = "201", description = "Confirmation request created")
    public ResponseEntity<ConfirmationRequestView> createConfirmationRequest(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID monthId,
        @Valid @RequestBody ConfirmationRequestInput request,
        @RequestHeader("If-Match") String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        ConfirmationRequestView result = confirmation.createRequest(
            jwt.getSubject(), monthId, request, ifMatch, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED)
            .eTag(String.valueOf(result.version()))
            .body(result);
    }

    @GetMapping("/confirmation-requests/{requestId}")
    @Operation(summary =
        "Read an authorized immutable confirmation scope, diff, actions and lineage")
    public ResponseEntity<ConfirmationRequestView> confirmationRequest(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID requestId
    ) {
        ConfirmationRequestView result = confirmation.request(
            jwt.getSubject(), requestId);
        return ResponseEntity.ok()
            .eTag(String.valueOf(result.version()))
            .body(result);
    }

    @PostMapping("/confirmation-requests/{requestId}/actions")
    @Operation(summary =
        "Record an in-app or request-bound single-use secure confirmation action")
    public ResponseEntity<ConfirmationRequestView> confirmationAction(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID requestId,
        @Valid @RequestBody ConfirmationActionRequest request,
        @RequestHeader("If-Match") String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        ConfirmationRequestView result = confirmation.act(
            jwt.getSubject(), requestId, request, ifMatch, idempotencyKey);
        return ResponseEntity.ok()
            .eTag(String.valueOf(result.version()))
            .body(result);
    }

    @PostMapping("/confirmation-requests/{requestId}/governance-decisions")
    @Operation(summary =
        "Resolve a mixed-action confirmation conflict through separated governance")
    @ApiResponse(responseCode = "201",
        description = "Append-only governance decision recorded")
    public ResponseEntity<GovernanceDecisionView> decideConfirmationConflict(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID requestId,
        @Valid @RequestBody GovernanceDecisionInput request,
        @RequestHeader("If-Match") String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        GovernanceDecisionView result = confirmation.decideConflict(
            jwt.getSubject(), requestId, request, ifMatch, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @PostMapping("/inbound-messages/{messageId}/reviews")
    @Operation(summary =
        "Append an authorized restricted review of safe inbound-message metadata")
    @ApiResponse(responseCode = "201", description = "Inbound review recorded")
    public ResponseEntity<InboundReviewView> reviewInboundMessage(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID messageId,
        @Valid @RequestBody InboundMessageReviewInput request,
        @RequestHeader("If-Match") String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        InboundReviewView result = reviews.reviewInbound(
            jwt.getSubject(), messageId, request, ifMatch, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED)
            .eTag(String.valueOf(result.version()))
            .body(result);
    }

    @PostMapping("/months/{monthId}/inbound-messages")
    @Operation(summary =
        "Record HMAC-authenticated provider-neutral inbound metadata for restricted review")
    @ApiResponse(responseCode = "201", description = "Inbound metadata recorded")
    public ResponseEntity<InboundReviewView> recordInboundMessage(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID monthId,
        @Valid @RequestBody InboundMessageRecordInput request,
        @RequestHeader("If-Match") String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestHeader("X-VMS-Inbound-Timestamp") long signatureTimestamp,
        @RequestHeader("X-VMS-Inbound-Signature") String signature
    ) {
        InboundReviewView result = reviews.recordInbound(
            jwt.getSubject(), monthId, request, ifMatch, idempotencyKey,
            signatureTimestamp, signature);
        return ResponseEntity.status(HttpStatus.CREATED)
            .eTag(String.valueOf(result.version()))
            .body(result);
    }

    @PostMapping("/months/{monthId}/manual-evidence")
    @Operation(summary =
        "Record restricted manual confirmation evidence for a distinct second review")
    @ApiResponse(responseCode = "201", description = "Manual evidence recorded")
    public ResponseEntity<InboundReviewView> recordManualEvidence(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID monthId,
        @Valid @RequestBody ManualEvidenceRecordInput request,
        @RequestHeader("If-Match") String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        InboundReviewView result = reviews.recordManualEvidence(
            jwt.getSubject(), monthId, request, ifMatch, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED)
            .eTag(String.valueOf(result.version()))
            .body(result);
    }

    @PostMapping("/manual-evidence/{evidenceId}/reviews")
    @Operation(summary =
        "Append a distinct authorized second review of safe manual-evidence metadata")
    @ApiResponse(responseCode = "201",
        description = "Manual evidence review recorded")
    public ResponseEntity<InboundReviewView> reviewManualEvidence(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID evidenceId,
        @Valid @RequestBody ManualEvidenceReviewInput request,
        @RequestHeader("If-Match") String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        InboundReviewView result = reviews.reviewManualEvidence(
            jwt.getSubject(), evidenceId, request, ifMatch, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED)
            .eTag(String.valueOf(result.version()))
            .body(result);
    }

    @PostMapping("/months/{monthId}/reopen-requests")
    @Operation(summary =
        "Create a reasoned reopen request and targeted invalidation lineage")
    @ApiResponse(responseCode = "201", description = "Reopen request created")
    public ResponseEntity<MonthCertificationView> requestReopen(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID monthId,
        @Valid @RequestBody ReopenRequestInput request,
        @RequestHeader("If-Match") String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        MonthCertificationView result = workflow.requestReopen(
            jwt.getSubject(), monthId, request, ifMatch, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED)
            .eTag(String.valueOf(result.version()))
            .body(result);
    }

    @PostMapping("/months/{monthId}/closures")
    @Operation(summary =
        "Create an immutable closure from the current verified confirmation and F05 manifest")
    @ApiResponse(responseCode = "201", description = "Month closure created")
    public ResponseEntity<MonthClosureView> closeMonth(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID monthId,
        @Valid @RequestBody CloseMonthInput request,
        @RequestHeader("If-Match") String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        MonthClosureView result = workflow.closeMonth(
            jwt.getSubject(), monthId, request, ifMatch, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED)
            .eTag(String.valueOf(result.version()))
            .body(result);
    }

    @PostMapping("/reopen-requests/{reopenRequestId}/decisions")
    @Operation(summary =
        "Append a distinct-authority decision to a pending reopen request")
    @ApiResponse(responseCode = "201", description = "Reopen decision recorded")
    public ResponseEntity<ReopenDecisionView> decideReopen(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID reopenRequestId,
        @Valid @RequestBody ReopenDecisionInput request,
        @RequestHeader("If-Match") String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        ReopenDecisionView result = workflow.decideReopen(
            jwt.getSubject(), reopenRequestId, request, ifMatch, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @PostMapping("/invalidations/{invalidationId}/resolutions")
    @Operation(summary =
        "Append an authorized resolution without mutating the invalidation fact")
    @ApiResponse(responseCode = "201",
        description = "Invalidation resolution recorded")
    public ResponseEntity<InvalidationResolutionView> resolveInvalidation(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID invalidationId,
        @Valid @RequestBody InvalidationResolutionInput request,
        @RequestHeader("If-Match") String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        InvalidationResolutionView result = workflow.resolveInvalidation(
            jwt.getSubject(), invalidationId, request, ifMatch, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @PostMapping("/notifications/{notificationId}/replays")
    @Operation(summary =
        "Authorize and audit replay of failed provider-neutral notification work")
    @ApiResponse(responseCode = "201", description = "Replay queued")
    public ResponseEntity<NotificationReplayView> replayNotification(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID notificationId,
        @Valid @RequestBody NotificationReplayInput request,
        @RequestHeader("If-Match") String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        NotificationReplayView result = operations.replayNotification(
            jwt.getSubject(), notificationId, request,
            ifMatch, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @PostMapping("/months/{monthId}/policy-versions")
    @Operation(summary =
        "Create an immutable active policy version and supersede only the prior active version")
    @ApiResponse(responseCode = "201", description = "Policy version created")
    public ResponseEntity<PolicyVersionView> createPolicyVersion(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID monthId,
        @Valid @RequestBody PolicyVersionInput request,
        @RequestHeader("If-Match") String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        PolicyVersionView result = policies.createPolicyVersion(
            jwt.getSubject(), monthId, request, ifMatch, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED)
            .eTag(String.valueOf(result.version()))
            .body(result);
    }

    @PostMapping("/submissions/{submissionId}/evidence-exceptions")
    @Operation(summary =
        "Append a separated authorized exception to a frozen evidence requirement")
    @ApiResponse(responseCode = "201", description = "Evidence exception approved")
    public ResponseEntity<EvidenceExceptionView> approveEvidenceException(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID submissionId,
        @Valid @RequestBody EvidenceExceptionInput request,
        @RequestHeader("If-Match") String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        EvidenceExceptionView result = policies.approveEvidenceException(
            jwt.getSubject(), submissionId, request,
            ifMatch, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @PostMapping("/months/{monthId}/attendance-exceptions")
    @Operation(summary =
        "Append an authorized disclosed attendance exception for readiness")
    @ApiResponse(responseCode = "201",
        description = "Attendance exception approved")
    public ResponseEntity<AttendanceExceptionView> approveAttendanceException(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID monthId,
        @Valid @RequestBody AttendanceExceptionInput request,
        @RequestHeader("If-Match") String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        AttendanceExceptionView result = policies.approveAttendanceException(
            jwt.getSubject(), monthId, request, ifMatch, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    private ResponseEntity<MonthCertificationView> monthResponse(
        MonthCertificationView value
    ) {
        return ResponseEntity.ok()
            .eTag(String.valueOf(value.version()))
            .body(value);
    }
}
