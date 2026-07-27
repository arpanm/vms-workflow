package com.vms.workflow.api;

import com.vms.workflow.application.FinanceArtifactGovernanceService;
import com.vms.workflow.application.FinanceGovernanceService;
import com.vms.workflow.application.FinanceInvoiceService;
import com.vms.workflow.application.FinancePackageService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/finance")
public class FinanceController {
    private final FinancePackageService packages;
    private final FinanceGovernanceService governance;
    private final FinanceInvoiceService invoices;
    private final FinanceArtifactGovernanceService artifacts;

    public FinanceController(
        FinancePackageService packages,
        FinanceGovernanceService governance,
        FinanceInvoiceService invoices,
        FinanceArtifactGovernanceService artifacts
    ) {
        this.packages = packages;
        this.governance = governance;
        this.invoices = invoices;
        this.artifacts = artifacts;
    }

    @GetMapping("/access")
    @Operation(summary = "Resolve the authenticated finance capability view")
    Map<String, Object> access(@AuthenticationPrincipal Jwt jwt) {
        return invoices.access(jwt.getSubject());
    }

    @PostMapping("/artifacts/{artifactId}/legal-hold")
    @Operation(summary = "Apply or release an authorized audited artifact legal hold")
    Map<String, Object> changeArtifactLegalHold(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID artifactId,
        @Valid @RequestBody LegalHoldInput input,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return artifacts.changeLegalHold(
            jwt.getSubject(), artifactId, input.enabled(),
            input.reasonCode(), idempotencyKey);
    }

    @GetMapping("/months")
    @Operation(summary = "Read scoped finance month summaries")
    Map<String, Object> months(
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam(required = false) String cursor
    ) {
        return invoices.months(jwt.getSubject(), cursor);
    }

    @GetMapping("/months/{monthId}")
    @Operation(summary = "Read a scoped finance month workspace")
    Map<String, Object> month(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID monthId
    ) {
        return invoices.workspace(jwt.getSubject(), monthId);
    }

    @GetMapping("/invoices")
    @Operation(summary = "Read scoped invoice summaries")
    Map<String, Object> invoiceList(
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam(required = false) UUID monthId,
        @RequestParam(required = false) String cursor
    ) {
        return invoices.invoices(jwt.getSubject(), monthId, cursor);
    }

    @PostMapping("/invoices")
    @Operation(summary = "Create a versioned invoice draft")
    ResponseEntity<Map<String, Object>> createInvoice(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody CreateInvoiceInput input,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        Map<String, Object> result = invoices.create(
            jwt.getSubject(), idempotencyKey, input);
        Object etag = result.getOrDefault("etag",
            result.getOrDefault("version", "1"));
        return ResponseEntity.status(201)
            .eTag(String.valueOf(etag))
            .body(result);
    }

    @GetMapping("/invoices/{invoiceId}")
    @Operation(summary = "Read invoice versions, readiness and review lineage")
    ResponseEntity<Map<String, Object>> invoice(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID invoiceId
    ) {
        return versioned(invoices.invoice(jwt.getSubject(), invoiceId));
    }

    @PostMapping("/invoices/{invoiceId}/document/download")
    @Operation(summary = "Download the authorized current scan-passed invoice document")
    ResponseEntity<byte[]> downloadInvoiceDocument(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID invoiceId
    ) {
        FinanceInvoiceService.InvoiceDocumentDownload result =
            invoices.downloadDocument(jwt.getSubject(), invoiceId);
        ContentDisposition disposition = ContentDisposition.attachment()
            .filename(result.safeName(), StandardCharsets.UTF_8)
            .build();
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(result.mediaType()))
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .contentLength(result.content().length)
            .body(result.content());
    }

    @PostMapping(
        value = "/invoices/{invoiceId}/documents",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Attach a private versioned invoice document")
    ResponseEntity<Map<String, Object>> uploadInvoiceDocument(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID invoiceId,
        @RequestPart("file") MultipartFile file,
        @Valid @RequestPart("metadata") UploadDocumentMetadata metadata,
        @RequestHeader("If-Match") String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        requireExpectedVersion(ifMatch, metadata.expectedVersion());
        return versioned(invoices.uploadDocument(
            jwt.getSubject(), invoiceId, ifMatch, idempotencyKey,
            file, metadata));
    }

    @PostMapping(
        value = "/invoices/{invoiceId}/documents/replace",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Replace an invoice document by appending a new version")
    ResponseEntity<Map<String, Object>> replaceInvoiceDocument(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID invoiceId,
        @RequestPart("file") MultipartFile file,
        @Valid @RequestPart("metadata") UploadDocumentMetadata metadata,
        @RequestHeader("If-Match") String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        requireExpectedVersion(ifMatch, metadata.expectedVersion());
        return versioned(invoices.uploadDocument(
            jwt.getSubject(), invoiceId, ifMatch, idempotencyKey,
            file, metadata));
    }

    @PostMapping("/invoices/{invoiceId}/readiness-runs")
    @Operation(summary = "Evaluate deterministic invoice submission readiness")
    ResponseEntity<Map<String, Object>> evaluateReadiness(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID invoiceId,
        @Valid @RequestBody ReadinessInput input,
        @RequestHeader("If-Match") String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        requireExpectedVersion(ifMatch, input.expectedVersion());
        return versioned(invoices.evaluateReadiness(
            jwt.getSubject(), invoiceId, ifMatch, idempotencyKey, input));
    }

    @PostMapping("/invoices/{invoiceId}/submit")
    @Operation(summary = "Submit an eligible exact invoice/package/readiness version")
    ResponseEntity<Map<String, Object>> submitInvoice(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID invoiceId,
        @Valid @RequestBody SubmitInvoiceInput input,
        @RequestHeader("If-Match") String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        requireExpectedVersion(ifMatch, input.expectedVersion());
        return versioned(invoices.submit(
            jwt.getSubject(), invoiceId, ifMatch, idempotencyKey, input));
    }

    @GetMapping("/months/{monthId}/packages")
    @Operation(summary = "Read immutable evidence package history")
    Map<String, Object> packageHistory(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID monthId,
        @RequestParam(required = false) String cursor
    ) {
        return packages.history(jwt.getSubject(), monthId, cursor);
    }

    @PostMapping("/months/{monthId}/packages")
    @Operation(summary = "Generate a canonical immutable evidence package")
    ResponseEntity<Map<String, Object>> generatePackage(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID monthId,
        @Valid @RequestBody GeneratePackageInput input,
        @RequestHeader("If-Match") String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        requireExpectedVersion(ifMatch, input.expectedMonthVersion());
        Map<String, Object> result = packages.generate(
            jwt.getSubject(), monthId, input.expectedMonthVersion(),
            input.readinessRunId(), input.reason(), idempotencyKey);
        return ResponseEntity.ok()
            .eTag(String.valueOf(input.expectedMonthVersion()))
            .body(result);
    }

    @GetMapping("/packages/{packageId}")
    @Operation(summary = "Read an immutable evidence package and provenance")
    Map<String, Object> packageView(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID packageId
    ) {
        return packages.packageView(jwt.getSubject(), packageId);
    }

    @GetMapping("/packages/{packageId}/diff")
    @Operation(summary = "Diff two package manifests within one finance month")
    Map<String, Object> packageDiff(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID packageId,
        @RequestParam("against") UUID against
    ) {
        return packages.diff(jwt.getSubject(), packageId, against);
    }

    @GetMapping("/packages/{packageId}/access-events")
    @Operation(summary = "Read restricted package access audit history")
    Map<String, Object> packageAccess(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID packageId,
        @RequestParam(required = false) String cursor
    ) {
        return packages.accessEvents(jwt.getSubject(), packageId, cursor);
    }

    @GetMapping("/packages/{packageId}/shares")
    @Operation(summary = "List explicit expiring package access grants")
    Map<String, Object> packageShares(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID packageId,
        @RequestParam(required = false) String cursor
    ) {
        return packages.shares(jwt.getSubject(), packageId, cursor);
    }

    @PostMapping("/packages/{packageId}/shares")
    @Operation(summary = "Create an authenticated expiring package access grant")
    ResponseEntity<Map<String, Object>> createPackageShare(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID packageId,
        @Valid @RequestBody PackageShareInput input,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return ResponseEntity.status(201).body(packages.createShare(
            jwt.getSubject(), packageId, input.recipientSubject(),
            input.accessScope(), input.expiresAt(), input.reason(),
            idempotencyKey));
    }

    @PostMapping("/packages/{packageId}/shares/{shareId}/revoke")
    @Operation(summary = "Revoke an explicit package access grant")
    Map<String, Object> revokePackageShare(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID packageId,
        @PathVariable UUID shareId,
        @Valid @RequestBody RevokePackageShareInput input,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return packages.revokeShare(
            jwt.getSubject(), packageId, shareId, input.reason(),
            idempotencyKey);
    }

    @PostMapping("/packages/{packageId}/artifacts/{artifactId}/download")
    @Operation(summary = "Download an integrity-verified package artifact")
    ResponseEntity<byte[]> downloadPackageArtifact(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID packageId,
        @PathVariable UUID artifactId
    ) {
        FinancePackageService.PackageDownloadResult result =
            packages.download(jwt.getSubject(), packageId, artifactId);
        ContentDisposition disposition = ContentDisposition.attachment()
            .filename(result.safeName(),
                StandardCharsets.UTF_8)
            .build();
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(result.mediaType()))
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .contentLength(result.content().length)
            .body(result.content());
    }

    @GetMapping("/procurement/control-tower")
    @Operation(summary = "Read the scoped Procurement readiness control tower")
    Map<String, Object> controlTower(
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam(required = false) String cursor
    ) {
        return governance.controlTower(jwt.getSubject(), cursor);
    }

    @PostMapping("/procurement/invoices/{invoiceId}/reviews")
    @Operation(summary = "Append a server-authorized Procurement decision")
    ResponseEntity<Map<String, Object>> reviewInvoice(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID invoiceId,
        @Valid @RequestBody ProcurementReviewInput input,
        @RequestHeader("If-Match") String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        requireExpectedVersion(ifMatch, input.expectedVersion());
        return versioned(governance.review(
            jwt.getSubject(), invoiceId, ifMatch, idempotencyKey, input));
    }

    @PostMapping("/procurement/invoices/{invoiceId}/queries")
    @Operation(summary = "Append a durable assigned Procurement query")
    ResponseEntity<Map<String, Object>> createQuery(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID invoiceId,
        @Valid @RequestBody ProcurementQueryInput input,
        @RequestHeader("If-Match") String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        requireExpectedVersion(ifMatch, input.expectedVersion());
        return versioned(governance.createQuery(
            jwt.getSubject(), invoiceId, ifMatch, idempotencyKey, input));
    }

    @PostMapping("/procurement/queries/{queryId}/responses")
    @Operation(summary = "Append an assigned-owner Procurement query response")
    Map<String, Object> respondProcurementQuery(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID queryId,
        @Valid @RequestBody ProcurementQueryResponseInput input,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return governance.respondQuery(
            jwt.getSubject(), queryId, input.response(), idempotencyKey);
    }

    @PostMapping("/procurement/queries/{queryId}/close")
    @Operation(summary = "Close or cancel a Procurement query")
    Map<String, Object> closeProcurementQuery(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID queryId,
        @Valid @RequestBody ProcurementQueryCloseInput input,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return governance.closeQuery(
            jwt.getSubject(), queryId, input.decision(), input.reason(),
            idempotencyKey);
    }

    @PostMapping("/procurement/invoices/{invoiceId}/exceptions")
    @Operation(summary = "Request a rule-bound Procurement exception")
    ResponseEntity<Map<String, Object>> acceptException(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID invoiceId,
        @Valid @RequestBody ProcurementExceptionInput input,
        @RequestHeader("If-Match") String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        requireExpectedVersion(ifMatch, input.expectedVersion());
        return versioned(governance.acceptException(
            jwt.getSubject(), invoiceId, ifMatch, idempotencyKey, input));
    }

    @PostMapping("/procurement/exceptions/{exceptionId}/second-approval")
    @Operation(
        summary = "Approve a pending exception as a distinct authenticated authority"
    )
    ResponseEntity<Map<String, Object>> approveException(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID exceptionId,
        @Valid @RequestBody ProcurementExceptionApprovalInput input,
        @RequestHeader("If-Match") String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        requireExpectedVersion(ifMatch, input.expectedVersion());
        return versioned(governance.approveException(
            jwt.getSubject(), exceptionId, ifMatch, idempotencyKey, input));
    }

    @GetMapping("/invoices/{invoiceId}/payments")
    @Operation(summary = "Read the append-only sanitized payment timeline")
    List<Map<String, Object>> paymentHistory(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID invoiceId
    ) {
        return governance.payments(jwt.getSubject(), invoiceId);
    }

    @PostMapping("/invoices/{invoiceId}/payments")
    @Operation(summary = "Append an authorized AP payment status fact")
    ResponseEntity<Map<String, Object>> updatePayment(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID invoiceId,
        @Valid @RequestBody PaymentUpdateInput input,
        @RequestHeader("If-Match") String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        requireExpectedVersion(ifMatch, input.expectedVersion());
        return versioned(governance.updatePayment(
            jwt.getSubject(), invoiceId, ifMatch, idempotencyKey, input));
    }

    @GetMapping("/dashboard")
    @Operation(summary = "Read scoped finance metrics and queues")
    Map<String, Object> dashboard(@AuthenticationPrincipal Jwt jwt) {
        return governance.dashboard(jwt.getSubject());
    }

    @GetMapping("/reports")
    @Operation(summary = "Read report definitions and asynchronous export history")
    Map<String, Object> reports(
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam(required = false) String cursor
    ) {
        return governance.reports(jwt.getSubject(), cursor);
    }

    @PostMapping("/exports")
    @Operation(summary = "Request an idempotent asynchronous report export")
    Map<String, Object> createExport(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody CreateExportInput input,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return governance.requestExport(jwt.getSubject(), idempotencyKey, input);
    }

    @GetMapping("/exports/{exportId}")
    @Operation(summary = "Read asynchronous export job state")
    Map<String, Object> exportStatus(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID exportId
    ) {
        return governance.exportStatus(jwt.getSubject(), exportId);
    }

    @PostMapping("/exports/{exportId}/replay")
    @Operation(summary = "Replay an authorized failed or dead-letter export")
    Map<String, Object> replayExport(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID exportId,
        @Valid @RequestBody ExportReplayInput input,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return governance.replayExport(
            jwt.getSubject(), exportId, input.reason(), idempotencyKey);
    }

    @PostMapping("/exports/{exportId}/download")
    @Operation(summary = "Download an authorized completed private export")
    ResponseEntity<byte[]> downloadExport(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID exportId
    ) {
        FinanceGovernanceService.ExportDownloadResult result =
            governance.exportDownload(jwt.getSubject(), exportId);
        ContentDisposition disposition = ContentDisposition.attachment()
            .filename(result.safeName(),
                StandardCharsets.UTF_8)
            .build();
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(result.mediaType()))
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .contentLength(result.content().length)
            .body(result.content());
    }

    private void requireExpectedVersion(String ifMatch, long expected) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new IllegalArgumentException("If-Match is required.");
        }
        String normalized = ifMatch.strip()
            .replaceFirst("^W/", "")
            .replace("\"", "");
        long header;
        try {
            header = Long.parseLong(normalized);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                "If-Match must contain a numeric version.", exception);
        }
        if (header != expected) {
            throw new DomainConflictException(
                "If-Match and expected version must match.");
        }
    }

    private ResponseEntity<Map<String, Object>> versioned(
        Map<String, Object> body
    ) {
        Object etag = body.get("etag");
        if (etag == null) {
            etag = body.getOrDefault("version", "1");
        }
        return ResponseEntity.ok().eTag(String.valueOf(etag)).body(body);
    }

    public record GeneratePackageInput(
        @Min(1) int expectedMonthVersion,
        @NotNull UUID readinessRunId,
        @NotBlank @Size(max = 1000) String reason
    ) {
    }

    public record LegalHoldInput(
        boolean enabled,
        @NotBlank @Size(max = 100) String reasonCode
    ) {
    }

    public record PackageShareInput(
        @NotBlank @Size(max = 255) String recipientSubject,
        @NotBlank @Size(max = 16) String accessScope,
        @NotNull OffsetDateTime expiresAt,
        @NotBlank @Size(max = 1000) String reason
    ) {
    }

    public record RevokePackageShareInput(
        @NotBlank @Size(max = 1000) String reason
    ) {
    }

    public record CreateInvoiceInput(
        @NotNull UUID monthId,
        @NotBlank String documentKind,
        UUID relatedInvoiceId,
        @NotNull RepresentedInvoiceMetadata representedMetadata
    ) {
    }

    public record RepresentedInvoiceMetadata(
        @NotBlank @Size(max = 160) String invoiceNumber,
        @NotNull LocalDate invoiceDate,
        @NotNull LocalDate billingPeriodStart,
        @NotNull LocalDate billingPeriodEnd,
        @NotBlank @Size(min = 3, max = 3) String currency,
        String taxableValue,
        String taxValue,
        String totalValue,
        @NotBlank @Size(max = 160) String purchaseOrderReference,
        @Size(max = 160) String workOrderReference
    ) {
    }

    public record UploadDocumentMetadata(
        @Min(1) long expectedVersion,
        @NotBlank @Size(max = 32) String classification,
        @NotBlank @Size(max = 64) String retentionPolicy,
        @NotBlank @Size(max = 48) String source,
        @NotBlank @Size(max = 1000) String reason
    ) {
    }

    public record ReadinessInput(@Min(1) long expectedVersion) {
    }

    public record SubmitInvoiceInput(
        @Min(1) long expectedVersion,
        @NotNull UUID packageId,
        @Min(1) int packageVersion,
        @NotNull UUID readinessRunId,
        boolean acknowledgment,
        @NotBlank @Size(max = 1000) String reason
    ) {
    }

    public record ProcurementReviewInput(
        @Min(1) long expectedVersion,
        @NotBlank String decision,
        @Size(max = 80) String category,
        @Size(max = 1000) String comment,
        @NotNull UUID packageId,
        @Min(1) int packageVersion,
        @NotNull UUID readinessRunId
    ) {
    }

    public record ProcurementQueryInput(
        @Min(1) long expectedVersion,
        @NotBlank @Size(max = 80) String category,
        @NotBlank @Size(max = 1000) String summary,
        @NotBlank @Size(max = 255) String ownerId,
        @NotNull OffsetDateTime dueAt,
        @NotBlank @Size(max = 1000) String reason
    ) {
    }

    public record ProcurementQueryResponseInput(
        @NotBlank @Size(max = 2000) String response
    ) {
    }

    public record ProcurementQueryCloseInput(
        @NotBlank @Size(max = 16) String decision,
        @NotBlank @Size(max = 1000) String reason
    ) {
    }

    public record ProcurementExceptionInput(
        @Min(1) long expectedVersion,
        @NotNull UUID ruleId,
        @NotNull UUID readinessRunId,
        @NotNull UUID packageId,
        @Min(1) int packageVersion,
        @NotBlank @Size(max = 1000) String rationale,
        @NotNull OffsetDateTime validUntil
    ) {
    }

    public record ProcurementExceptionApprovalInput(
        @Min(1) long expectedVersion,
        @NotNull UUID invoiceId,
        @NotNull UUID ruleId,
        @NotNull UUID readinessRunId,
        @NotNull UUID packageId,
        @Min(1) int packageVersion,
        @NotNull UUID policyVersionId,
        @Min(1) int policyVersion
    ) {
    }

    public record PaymentUpdateInput(
        @Min(1) long expectedVersion,
        @NotBlank String status,
        @NotNull OffsetDateTime statusAt,
        LocalDate expectedPaymentDate,
        LocalDate actualPaymentDate,
        @Size(max = 160) String externalReference,
        @NotBlank @Size(max = 500) String comment
    ) {
    }

    public record CreateExportInput(
        @NotBlank @Size(max = 80) String reportId,
        @NotBlank @Size(max = 32) String reportVersion,
        @NotBlank @Size(max = 16) String format,
        @NotBlank String temporalMode,
        @NotNull Map<String, Object> filters,
        @NotBlank @Size(max = 1000) String reason
    ) {
    }

    public record ExportReplayInput(
        @NotBlank @Size(max = 1000) String reason
    ) {
    }
}
