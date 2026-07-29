package com.vms.workflow.api;

import com.vms.workflow.api.GreytHrDtos.CapabilityCertificationRequest;
import com.vms.workflow.api.GreytHrDtos.CapabilityView;
import com.vms.workflow.api.GreytHrDtos.CutoverRequest;
import com.vms.workflow.api.GreytHrDtos.CutoverView;
import com.vms.workflow.api.GreytHrDtos.HealthView;
import com.vms.workflow.api.GreytHrDtos.ReconciliationDecisionRequest;
import com.vms.workflow.api.GreytHrDtos.ReconciliationView;
import com.vms.workflow.api.GreytHrDtos.SyncRequest;
import com.vms.workflow.api.GreytHrDtos.SyncRunView;
import com.vms.workflow.application.GreytHrIntegrationService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/integrations/greythr")
public class GreytHrIntegrationController {
    private final GreytHrIntegrationService greytHr;

    public GreytHrIntegrationController(GreytHrIntegrationService greytHr) {
        this.greytHr = greytHr;
    }

    @GetMapping("/connections/{connectionId}/capabilities")
    @Operation(summary = "Discover the secret-redacted provider-neutral capability contract")
    CapabilityView capabilities(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID connectionId
    ) {
        return greytHr.capabilities(jwt.getSubject(), connectionId);
    }

    @PostMapping("/connections/{connectionId}/certifications")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Certify the exact greytHR capabilities required before authority cutover")
    CapabilityView certify(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID connectionId,
        @Valid @RequestBody CapabilityCertificationRequest request
    ) {
        return greytHr.certify(jwt.getSubject(), connectionId, request);
    }

    @PostMapping("/connections/{connectionId}/sync-runs")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Run or replay one durable, page-safe greytHR synchronization")
    SyncRunView sync(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID connectionId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody SyncRequest request
    ) {
        return greytHr.sync(
            jwt.getSubject(), connectionId, idempotencyKey, request);
    }

    @GetMapping("/connections/{connectionId}/sync-runs/{runId}")
    @Operation(summary = "Read a durable greytHR synchronization result and freshness")
    SyncRunView syncRun(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID connectionId,
        @PathVariable UUID runId
    ) {
        return greytHr.syncRun(jwt.getSubject(), connectionId, runId);
    }

    @GetMapping("/connections/{connectionId}/reconciliations")
    @Operation(summary = "List source conflicts without inferring an authority decision")
    List<ReconciliationView> reconciliations(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID connectionId
    ) {
        return greytHr.reconciliations(jwt.getSubject(), connectionId);
    }

    @PostMapping("/reconciliations/{itemId}/decisions")
    @Operation(summary = "Resolve one greytHR/internal conflict with an explicit reason")
    ReconciliationView reconcile(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID itemId,
        @Valid @RequestBody ReconciliationDecisionRequest request
    ) {
        return greytHr.reconcile(jwt.getSubject(), itemId, request);
    }

    @PostMapping("/connections/{connectionId}/cutovers")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an effective-dated greytHR-authoritative source cutover")
    CutoverView cutover(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID connectionId,
        @Valid @RequestBody CutoverRequest request
    ) {
        return greytHr.cutover(jwt.getSubject(), connectionId, request);
    }

    @GetMapping("/connections/{connectionId}/health")
    @Operation(summary = "Read explicit greytHR availability, freshness and reconciliation health")
    HealthView health(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID connectionId
    ) {
        return greytHr.health(jwt.getSubject(), connectionId);
    }
}
