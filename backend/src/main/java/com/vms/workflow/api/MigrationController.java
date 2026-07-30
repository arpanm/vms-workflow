package com.vms.workflow.api;

import com.vms.workflow.application.MigrationService;
import com.vms.workflow.application.MigrationTemplateRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/migrations")
public class MigrationController {
    private final MigrationService migrations;

    public MigrationController(MigrationService migrations) {
        this.migrations = migrations;
    }

    @GetMapping("/access")
    @Operation(summary = "Resolve scoped migration capabilities")
    Map<String, Object> access(
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam(required = false) UUID engagementId
    ) {
        return migrations.access(jwt.getSubject(), engagementId);
    }

    @GetMapping("/templates")
    @Operation(summary = "List all 14 governed CSV template contracts")
    List<MigrationTemplateRegistry.Template> templates(
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam UUID engagementId
    ) {
        return migrations.templates(jwt.getSubject(), engagementId);
    }

    @GetMapping("/templates/{templateCode}/download")
    @Operation(summary = "Download a formula-safe synthetic CSV template")
    ResponseEntity<byte[]> template(
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam UUID engagementId,
        @RequestParam(defaultValue = "CSV") String format,
        @PathVariable String templateCode
    ) {
        return download(migrations.sample(
            jwt.getSubject(), engagementId, templateCode, format));
    }

    @GetMapping("/jobs")
    @Operation(summary = "List non-enumerable scoped migration jobs")
    Map<String, Object> jobs(
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam UUID engagementId,
        @RequestParam(required = false) UUID organizationId,
        @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
        @RequestParam(required = false) String cursor
    ) {
        return migrations.jobs(
            jwt.getSubject(), engagementId, organizationId, limit, cursor);
    }

    @PostMapping(
        value = "/jobs",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
        summary = "Upload an immutable scanned CSV and create a dry-run job",
        responses = @ApiResponse(responseCode = "200",
            description = "Idempotent source and job result"))
    ResponseEntity<Map<String, Object>> upload(
        @AuthenticationPrincipal Jwt jwt,
        @RequestPart("file") MultipartFile file,
        @Valid @RequestPart("metadata") MigrationDtos.UploadMetadata metadata
    ) {
        return versioned(migrations.upload(jwt.getSubject(), file, metadata));
    }

    @GetMapping("/jobs/{jobId}")
    @Operation(summary = "Read migration lifecycle, counts and approvals")
    ResponseEntity<Map<String, Object>> job(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID jobId
    ) {
        return versioned(migrations.job(jwt.getSubject(), jobId));
    }

    @GetMapping("/jobs/{jobId}/rows")
    @Operation(summary = "Page safe staging-row metadata and findings")
    Map<String, Object> rows(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID jobId,
        @RequestParam(required = false) String state,
        @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit,
        @RequestParam(defaultValue = "1") @Min(1) int afterRow
    ) {
        return migrations.rows(
            jwt.getSubject(), jobId, state, limit, afterRow);
    }

    @GetMapping("/jobs/{jobId}/correction-plan")
    @Operation(summary =
        "Resolve the governed reopen and superseding-package correction path")
    Map<String, Object> correctionPlan(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID jobId
    ) {
        return migrations.correctionPlan(jwt.getSubject(), jobId);
    }

    @PostMapping("/jobs/{jobId}/validate")
    @Operation(summary = "Scan, RFC-4180 parse and validate a staged job")
    ResponseEntity<Map<String, Object>> validate(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID jobId,
        @Valid @org.springframework.web.bind.annotation.RequestBody
        MigrationDtos.VersionInput input,
        @RequestHeader("If-Match") String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        requireVersion(ifMatch, input.expectedVersion());
        return versioned(migrations.validate(
            jwt.getSubject(), jobId, input.expectedVersion(),
            idempotencyKey));
    }

    @PostMapping("/jobs/{jobId}/validation-runs")
    @Operation(
        summary = "Queue durable asynchronous scan and validation execution")
    ResponseEntity<Map<String, Object>> queueValidation(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID jobId,
        @Valid @org.springframework.web.bind.annotation.RequestBody
        MigrationDtos.VersionInput input,
        @RequestHeader("If-Match") String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        requireVersion(ifMatch, input.expectedVersion());
        Map<String, Object> result = migrations.queueValidation(
            jwt.getSubject(), jobId, input.expectedVersion(),
            idempotencyKey);
        return ResponseEntity.accepted()
            .eTag("\"" + result.get("version") + "\"")
            .body(result);
    }

    @PostMapping("/jobs/{jobId}/rows/{rowId}/resolution")
    @Operation(summary = "Resolve an exact duplicate conflict")
    ResponseEntity<Map<String, Object>> resolve(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID jobId,
        @PathVariable UUID rowId,
        @Valid @org.springframework.web.bind.annotation.RequestBody
        MigrationDtos.ResolveConflictInput input,
        @RequestHeader("If-Match") String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        requireVersion(ifMatch, input.expectedVersion());
        return versioned(migrations.resolve(
            jwt.getSubject(), jobId, rowId, input, idempotencyKey));
    }

    @PostMapping({"/jobs/{jobId}/approval", "/jobs/{jobId}/approvals"})
    @Operation(summary = "Record an exact-version migration approval")
    ResponseEntity<Map<String, Object>> approve(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID jobId,
        @Valid @org.springframework.web.bind.annotation.RequestBody
        MigrationDtos.ApprovalInput input,
        @RequestHeader("If-Match") String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        requireVersion(ifMatch, input.expectedVersion());
        return versioned(migrations.approve(
            jwt.getSubject(), jobId, input, idempotencyKey));
    }

    @PostMapping("/jobs/{jobId}/commit")
    @Operation(summary = "Commit valid rows after exact dual approval")
    ResponseEntity<Map<String, Object>> commit(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID jobId,
        @Valid @org.springframework.web.bind.annotation.RequestBody
        MigrationDtos.CommitInput input,
        @RequestHeader("If-Match") String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        requireVersion(ifMatch, input.expectedVersion());
        return versioned(migrations.commit(
            jwt.getSubject(), jobId, input.expectedVersion(),
            input.partialCommit(),
            idempotencyKey));
    }

    @PostMapping("/jobs/{jobId}/reprocess")
    @Operation(summary = "Create an idempotent rejected-row reprocess job")
    ResponseEntity<Map<String, Object>> reprocess(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID jobId,
        @Valid @org.springframework.web.bind.annotation.RequestBody
        MigrationDtos.VersionInput input,
        @RequestHeader("If-Match") String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        requireVersion(ifMatch, input.expectedVersion());
        return versioned(migrations.reprocess(
            jwt.getSubject(), jobId, input.expectedVersion(),
            idempotencyKey));
    }

    @PostMapping("/jobs/{jobId}/retry")
    @Operation(
        summary = "Retry pending scan/validation work or replay a cancelled job")
    ResponseEntity<Map<String, Object>> retry(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID jobId,
        @Valid @org.springframework.web.bind.annotation.RequestBody
        MigrationDtos.ReasonInput input,
        @RequestHeader("If-Match") String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        requireVersion(ifMatch, input.expectedVersion());
        return versioned(migrations.retry(
            jwt.getSubject(), jobId, input.expectedVersion(),
            input.reason(), idempotencyKey));
    }

    @PostMapping("/jobs/{jobId}/cancel")
    @Operation(summary = "Cancel a pre-commit migration without effects")
    ResponseEntity<Map<String, Object>> cancel(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID jobId,
        @Valid @org.springframework.web.bind.annotation.RequestBody
        MigrationDtos.ReasonInput input,
        @RequestHeader("If-Match") String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        requireVersion(ifMatch, input.expectedVersion());
        return versioned(migrations.cancel(
            jwt.getSubject(), jobId, input.expectedVersion(),
            input.reason(), idempotencyKey));
    }

    @PostMapping("/jobs/{jobId}/rollback")
    @Operation(summary = "Compensate an unconsumed committed batch")
    ResponseEntity<Map<String, Object>> rollback(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID jobId,
        @Valid @org.springframework.web.bind.annotation.RequestBody
        MigrationDtos.RollbackInput input,
        @RequestHeader("If-Match") String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        requireVersion(ifMatch, input.expectedVersion());
        return versioned(migrations.rollback(
            jwt.getSubject(), jobId, input, idempotencyKey));
    }

    @GetMapping("/jobs/{jobId}/errors/download")
    @Operation(summary = "Download an audited formula-safe error report")
    ResponseEntity<byte[]> errors(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID jobId
    ) {
        return download(migrations.errors(jwt.getSubject(), jobId));
    }

    @GetMapping("/jobs/{jobId}/reconciliation")
    @Operation(summary = "Read immutable reconciliation and sign-offs")
    Map<String, Object> reconciliation(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID jobId
    ) {
        return migrations.reconciliation(jwt.getSubject(), jobId);
    }

    @GetMapping("/jobs/{jobId}/audit")
    @Operation(summary = "Read the restricted redacted migration audit trail")
    List<Map<String, Object>> audit(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID jobId
    ) {
        return migrations.auditTrail(jwt.getSubject(), jobId);
    }

    @PostMapping("/reconciliations/{reportId}/sign-offs")
    @Operation(summary = "Sign the exact reconciliation version and hash")
    Map<String, Object> signOff(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID reportId,
        @Valid @org.springframework.web.bind.annotation.RequestBody
        MigrationDtos.SignOffInput input,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return migrations.signOff(
            jwt.getSubject(), reportId, input, idempotencyKey);
    }

    @PostMapping("/retro-requests")
    @Operation(summary = "Create a plainly historical current-time request")
    ResponseEntity<Map<String, Object>> retro(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @org.springframework.web.bind.annotation.RequestBody
        MigrationDtos.RetroRequestInput input,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return ResponseEntity.status(201).body(migrations.retro(
            jwt.getSubject(), input, idempotencyKey));
    }

    @GetMapping("/retro-requests")
    @Operation(summary = "List scoped pending and completed historical requests")
    Map<String, Object> retroRequests(
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam UUID engagementId,
        @RequestParam(required = false) String state,
        @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit
    ) {
        return migrations.retroRequests(
            jwt.getSubject(), engagementId, state, limit);
    }

    @PostMapping("/retro-requests/{requestId}/decision")
    @Operation(summary = "Record a current-time historical request decision")
    ResponseEntity<Map<String, Object>> decideRetro(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID requestId,
        @Valid @org.springframework.web.bind.annotation.RequestBody
        MigrationDtos.RetroDecisionInput input,
        @RequestHeader("If-Match") String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        requireVersion(ifMatch, input.expectedVersion());
        return versioned(migrations.decideRetro(
            jwt.getSubject(), requestId, input, idempotencyKey));
    }

    @PostMapping("/retro-requests/{requestId}/cancel")
    @Operation(summary = "Cancel a pending historical request")
    ResponseEntity<Map<String, Object>> cancelRetro(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID requestId,
        @Valid @org.springframework.web.bind.annotation.RequestBody
        MigrationDtos.ReasonInput input,
        @RequestHeader("If-Match") String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        requireVersion(ifMatch, input.expectedVersion());
        return versioned(migrations.cancelRetro(
            jwt.getSubject(), requestId, input, idempotencyKey));
    }

    @GetMapping("/months/{monthId}/readiness")
    @Operation(summary = "Read historical month lifecycle and blocking tasks")
    Map<String, Object> monthReadiness(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID monthId
    ) {
        return migrations.monthReadiness(jwt.getSubject(), monthId);
    }

    @PostMapping("/months/{monthId}/transitions")
    @Operation(summary = "Advance a historical month after server-side readiness")
    ResponseEntity<Map<String, Object>> transitionMonth(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID monthId,
        @Valid @org.springframework.web.bind.annotation.RequestBody
        MigrationDtos.MonthTransitionInput input,
        @RequestHeader("If-Match") String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        requireVersion(ifMatch, input.expectedVersion());
        return versioned(migrations.transitionMonth(
            jwt.getSubject(), monthId, input, idempotencyKey));
    }

    private ResponseEntity<Map<String, Object>> versioned(
        Map<String, Object> value
    ) {
        return ResponseEntity.ok()
            .eTag(String.valueOf(value.getOrDefault("version", "1")))
            .body(value);
    }

    private ResponseEntity<byte[]> download(MigrationService.Download value) {
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(value.mediaType()))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment()
                    .filename(value.filename(), StandardCharsets.UTF_8)
                    .build().toString())
            .contentLength(value.content().length)
            .body(value.content());
    }

    private void requireVersion(String ifMatch, long expected) {
        String normalized = ifMatch.replace("\"", "").trim();
        if (!normalized.equals(Long.toString(expected))) {
            throw new DomainConflictException(
                "ETAG_BODY_MISMATCH",
                "If-Match and expectedVersion must identify the same version.");
        }
    }
}
