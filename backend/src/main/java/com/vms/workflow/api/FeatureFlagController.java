package com.vms.workflow.api;

import com.vms.workflow.application.FeatureFlagService;
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

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/governance/feature-flags")
public class FeatureFlagController {
    private final FeatureFlagService flags;

    public FeatureFlagController(FeatureFlagService flags) {
        this.flags = flags;
    }

    @PostMapping
    @Operation(summary = "Define an immutable server-authoritative feature flag")
    Map<String, Object> define(
        @AuthenticationPrincipal Jwt jwt,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody FeatureFlagDtos.DefinitionInput input
    ) {
        return flags.define(jwt.getSubject(), idempotencyKey, input);
    }

    @PostMapping("/{key}/versions")
    @Operation(summary = "Append an audited scoped feature-flag version")
    Map<String, Object> version(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable String key,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody FeatureFlagDtos.VersionInput input
    ) {
        return flags.version(jwt.getSubject(), key, idempotencyKey, input);
    }

    @GetMapping("/{key}/evaluation")
    @Operation(summary = "Evaluate effective feature state without granting authority")
    Map<String, Object> evaluate(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable String key,
        @RequestParam UUID organizationId,
        @RequestParam(required = false) UUID engagementId
    ) {
        return flags.evaluate(
            jwt.getSubject(), key, organizationId, engagementId);
    }
}
