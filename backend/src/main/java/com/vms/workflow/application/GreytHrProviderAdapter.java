package com.vms.workflow.application;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface GreytHrProviderAdapter {
    CapabilityProbeResult probe(UUID connectionId);

    FetchResult fetch(UUID connectionId, LocalDate dateFrom, LocalDate dateTo);

    record CapabilityProbeResult(
        String status,
        String errorCode,
        String adapterMode,
        List<String> capabilities,
        Map<String, Object> evidence
    ) {
        public CapabilityProbeResult {
            capabilities = List.copyOf(capabilities);
            evidence = Map.copyOf(evidence);
        }

        public static CapabilityProbeResult success(
            String adapterMode,
            List<String> capabilities,
            Map<String, Object> evidence
        ) {
            return new CapabilityProbeResult(
                "AVAILABLE", null, adapterMode, capabilities, evidence);
        }

        public static CapabilityProbeResult failure(
            String errorCode,
            String adapterMode
        ) {
            return new CapabilityProbeResult(
                "DEGRADED", errorCode, adapterMode, List.of(), Map.of());
        }
    }

    record ProviderPage(
        int pageNumber,
        OffsetDateTime sourceUpdatedAt,
        Map<String, Object> payload
    ) {
    }

    record FetchResult(
        String status,
        String errorCode,
        List<ProviderPage> pages
    ) {
        public static FetchResult success(List<ProviderPage> pages) {
            return new FetchResult("AVAILABLE", null, List.copyOf(pages));
        }

        public static FetchResult failure(String errorCode) {
            return new FetchResult("DEGRADED", errorCode, List.of());
        }
    }
}
