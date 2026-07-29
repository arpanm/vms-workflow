package com.vms.workflow.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class FeatureFlagDtos {
    private FeatureFlagDtos() {
    }

    public record DefinitionInput(
        @NotBlank
        @Pattern(regexp = "^[a-z][a-z0-9]*(\\.[a-z0-9]+)*$")
        @Size(max = 100)
        String key,
        @NotBlank @Size(max = 120) String owner,
        boolean defaultEnabled,
        @NotBlank @Size(max = 500) String description,
        @NotBlank @Size(max = 300) String reason
    ) {
    }

    public record VersionInput(
        @NotBlank
        @Pattern(regexp = "SYSTEM|ORGANIZATION|ENGAGEMENT")
        String scopeType,
        UUID organizationId,
        UUID engagementId,
        boolean enabled,
        @NotNull OffsetDateTime effectiveFrom,
        OffsetDateTime effectiveUntil,
        @Size(max = 20) List<
            @Pattern(regexp = "^[a-z][a-z0-9]*(\\.[a-z0-9]+)*$")
            @Size(max = 100) String> dependencies,
        @NotBlank @Size(max = 300) String reason
    ) {
        public VersionInput {
            dependencies = dependencies == null ? List.of() : List.copyOf(
                dependencies);
        }
    }
}
