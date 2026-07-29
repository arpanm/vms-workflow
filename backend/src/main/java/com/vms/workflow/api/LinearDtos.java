package com.vms.workflow.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class LinearDtos {
    private LinearDtos() {
    }

    public record LinkIssueRequest(
        @NotNull UUID deliverableVersionId,
        @NotNull UUID connectionId,
        @NotNull UUID issueUuid,
        @Size(max = 2_000) String rationale
    ) {
    }

    public record IssueLinkView(
        UUID id,
        UUID deliverableVersionId,
        UUID connectionId,
        UUID issueUuid,
        String identifier,
        String url,
        @Schema(allowableValues = {"ACTIVE", "BROKEN", "INACCESSIBLE"})
        String status,
        String rationale,
        String currentNormalizedState,
        OffsetDateTime lastFetchedAt
    ) {
    }

    public record IssueCurrentView(
        UUID issueUuid,
        String identifier,
        String url,
        String title,
        String providerStateId,
        String providerStateName,
        String providerStateType,
        String providerStateCategory,
        String normalizedState,
        OffsetDateTime updatedAt,
        OffsetDateTime fetchedAt,
        String payloadHash,
        boolean stale,
        boolean inaccessible,
        String executionProjection
    ) {
    }

    public record IssueSnapshotView(
        UUID id,
        String snapshotType,
        @Schema(allowableValues = {"CAPTURED", "FETCH_FAILED", "UNAVAILABLE"})
        String status,
        String normalizedState,
        String providerStateId,
        String providerStateName,
        String providerStateType,
        String providerStateCategory,
        OffsetDateTime fetchedAt,
        String payloadHash,
        String confidence,
        String failureReason
    ) {
    }

    public record LinearHealthView(
        UUID connectionId,
        String status,
        @Schema(allowableValues = {
            "EXTERNALLY_BLOCKED", "NOT_CONFIGURED", "CONFIGURED"
        })
        String providerRegistrationStatus,
        OffsetDateTime lastVerifiedDeliveryAt,
        OffsetDateTime lastReconciledAt,
        int linkedIssueCount,
        int staleIssueCount,
        int queuedCount,
        int deadLetterCount,
        String lastError
    ) {
    }

    public record WebhookAcceptedView(
        UUID deliveryId,
        boolean duplicate,
        String queueStatus
    ) {
    }

    public record WebhookProcessView(
        UUID deliveryId,
        UUID queueId,
        String status,
        int attemptCount,
        OffsetDateTime processedAt,
        boolean duplicate
    ) {
    }

    public record LinearReconciliationRequest(
        @NotBlank @Pattern(regexp = "AVAILABLE|UNAVAILABLE") String outcome,
        @Pattern(
            regexp = "PROVIDER_UNAVAILABLE|AUTHENTICATION_FAILED|"
                + "RATE_LIMITED|SCHEMA_INVALID|PROVIDER_TIMEOUT"
        )
        String errorCode,
        @NotBlank @Size(max = 4_000) String reason
    ) {
    }

    public record LinearReconciliationView(
        UUID jobId,
        UUID connectionId,
        String jobStatus,
        String connectionStatus,
        int staleIssueCount,
        OffsetDateTime recordedAt,
        String errorCode,
        String commandChecksum,
        UUID correlationId,
        UUID causationId,
        boolean replay
    ) {
    }

    public record ConnectionMetadataView(
        UUID id,
        UUID engagementId,
        String providerOrganizationId,
        String displayName,
        String status,
        String providerRegistrationStatus,
        @Schema(description = "True when a server-side secret reference is configured; the reference is never returned.")
        boolean credentialReferenceConfigured,
        boolean webhookReferenceConfigured
    ) {
    }
}
