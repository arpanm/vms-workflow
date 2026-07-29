package com.vms.workflow.api;

import com.vms.workflow.application.RetentionPrivacyService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/governance/retention")
public class RetentionPrivacyController {
    private final RetentionPrivacyService retention;

    public RetentionPrivacyController(RetentionPrivacyService retention) {
        this.retention = retention;
    }

    @PostMapping("/schedules")
    @Operation(summary = "Version an organization-scoped retention schedule")
    Map<String, Object> createSchedule(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody RetentionDtos.ScheduleInput input,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return retention.createSchedule(jwt.getSubject(), input, idempotencyKey);
    }

    @GetMapping("/schedules")
    @Operation(summary = "List configured retention schedule versions")
    List<Map<String, Object>> schedules(
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam UUID organizationId
    ) {
        return retention.schedules(jwt.getSubject(), organizationId);
    }

    @PostMapping("/runs/dry-run")
    @Operation(summary = "Record a deterministic capability-expiry dry run")
    Map<String, Object> dryRun(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody RetentionDtos.DryRunInput input,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return retention.dryRun(jwt.getSubject(), input, idempotencyKey);
    }

    @PostMapping("/runs/{runId}/execute")
    @Operation(summary = "Execute or retry capability expiry from a dry run")
    Map<String, Object> execute(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID runId,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return retention.execute(jwt.getSubject(), runId, idempotencyKey);
    }

    @PostMapping("/runs/{runId}/dead-letter-recovery")
    @Operation(summary = "Authorize a new bounded cycle for a dead-lettered retention run")
    Map<String, Object> authorizeRecovery(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID runId,
        @Valid @RequestBody RetentionDtos.RecoveryInput input,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return retention.authorizeRecovery(
            jwt.getSubject(), runId, input, idempotencyKey);
    }

    @GetMapping("/runs/{runId}")
    @Operation(summary = "Read a retention candidate, skip and proof report")
    Map<String, Object> run(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID runId
    ) {
        return retention.run(jwt.getSubject(), runId);
    }

    @PostMapping("/artifacts/{artifactId}/holds")
    @Operation(summary = "Place an audited legal hold")
    Map<String, Object> placeHold(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID artifactId,
        @Valid @RequestBody RetentionDtos.HoldInput input,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return retention.placeHold(
            jwt.getSubject(), artifactId, input, idempotencyKey);
    }

    @PostMapping("/artifacts/{artifactId}/holds/{holdId}/release")
    @Operation(summary = "Release or request dual-control legal-hold release")
    Map<String, Object> requestRelease(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID artifactId,
        @PathVariable UUID holdId,
        @Valid @RequestBody RetentionDtos.ReleaseInput input,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return retention.requestRelease(
            jwt.getSubject(), artifactId, holdId, input, idempotencyKey);
    }

    @PostMapping("/artifacts/{artifactId}/holds/{holdId}/release-approval")
    @Operation(summary = "Approve legal-hold release as a distinct actor")
    Map<String, Object> approveRelease(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID artifactId,
        @PathVariable UUID holdId,
        @Valid @RequestBody RetentionDtos.ReleaseInput input,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return retention.approveRelease(
            jwt.getSubject(), artifactId, holdId, input, idempotencyKey);
    }

    @GetMapping("/classification")
    @Operation(summary = "Read the local data-classification inventory")
    List<Map<String, Object>> classification(
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam UUID organizationId
    ) {
        return retention.classification(jwt.getSubject(), organizationId);
    }
}
