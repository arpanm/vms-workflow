package com.vms.workflow.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@Profile("system-e2e & !prod")
public class RecordedGreytHrProviderAdapter implements GreytHrProviderAdapter {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public RecordedGreytHrProviderAdapter(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public CapabilityProbeResult probe(UUID connectionId) {
        String mode = adapterMode(connectionId);
        if (!"RECORDED_FIXTURE".equals(mode)) {
            return CapabilityProbeResult.failure(
                "PROVIDER_NOT_CONFIGURED",
                mode == null ? "NOT_CONFIGURED" : mode);
        }
        List<RecordedRow> rows = recordedRows(connectionId);
        if (rows.isEmpty()) {
            return CapabilityProbeResult.failure(
                "PROVIDER_EMPTY_RESPONSE", mode);
        }
        if (rows.size() > 100) {
            return CapabilityProbeResult.failure(
                "PROVIDER_MALFORMED_RESPONSE", mode);
        }
        for (RecordedRow row : rows) {
            if (!"AVAILABLE".equals(row.responseMode())) {
                return CapabilityProbeResult.failure(
                    responseFailureCode(row.responseMode()), mode);
            }
        }
        try {
            LinkedHashSet<String> capabilities = new LinkedHashSet<>();
            List<Map<String, Object>> pages = new ArrayList<>();
            for (RecordedRow row : rows) {
                Map<String, Object> payload = mapper.readValue(
                    row.payload(),
                    new TypeReference<Map<String, Object>>() {
                    });
                discover(payload, "employees", "EMPLOYEES", capabilities);
                discover(payload, "attendance", "ATTENDANCE", capabilities);
                discover(payload, "leave", "LEAVE", capabilities);
                pages.add(Map.of(
                    "pageNumber", row.pageNumber(),
                    "sourceUpdatedAt", row.sourceUpdatedAt().toString(),
                    "payloadHash", sha256(row.payload())
                ));
            }
            return CapabilityProbeResult.success(
                mode,
                capabilities.stream().sorted().toList(),
                Map.of(
                    "schema", "greythr-provider-probe-v1",
                    "authority", "SIMULATED_NON_PRODUCTION",
                    "pageCount", rows.size(),
                    "pages", pages
                ));
        } catch (RuntimeException exception) {
            return CapabilityProbeResult.failure(
                "PROVIDER_MALFORMED_RESPONSE", mode);
        }
    }

    @Override
    public FetchResult fetch(UUID connectionId, LocalDate dateFrom, LocalDate dateTo) {
        String mode = adapterMode(connectionId);
        if (!"RECORDED_FIXTURE".equals(mode)) {
            return FetchResult.failure("PROVIDER_NOT_CONFIGURED");
        }
        List<RecordedRow> rows = recordedRows(connectionId);
        if (rows.isEmpty()) {
            return FetchResult.failure("PROVIDER_EMPTY_RESPONSE");
        }
        if (rows.size() > 100) {
            return FetchResult.failure("PROVIDER_MALFORMED_RESPONSE");
        }
        for (RecordedRow row : rows) {
            if (!"AVAILABLE".equals(row.responseMode())) {
                return FetchResult.failure(
                    responseFailureCode(row.responseMode()));
            }
        }
        try {
            return FetchResult.success(rows.stream().map(row -> new ProviderPage(
                row.pageNumber(),
                row.sourceUpdatedAt(),
                mapper.readValue(
                    row.payload(), new TypeReference<Map<String, Object>>() {
                    })
            )).toList());
        } catch (RuntimeException exception) {
            return FetchResult.failure("PROVIDER_MALFORMED_RESPONSE");
        }
    }

    private String adapterMode(UUID connectionId) {
        return jdbc.query("""
            SELECT adapter_mode FROM greythr_connections WHERE id = ?
            """, result -> result.next() ? result.getString(1) : null, connectionId);
    }

    private List<RecordedRow> recordedRows(UUID connectionId) {
        return jdbc.query("""
            SELECT page_number, response_mode, payload::text, source_updated_at
            FROM greythr_recorded_pages
            WHERE connection_id = ?
            ORDER BY page_number
            LIMIT 101
            """, (result, row) -> new RecordedRow(
                result.getInt("page_number"),
                result.getString("response_mode"),
                result.getString("payload"),
                result.getObject("source_updated_at", OffsetDateTime.class)
            ), connectionId);
    }

    private void discover(
        Map<String, Object> payload,
        String field,
        String capability,
        LinkedHashSet<String> capabilities
    ) {
        Object value = payload.get(field);
        if (value instanceof List<?> records && !records.isEmpty()) {
            capabilities.add(capability);
        }
    }

    private String responseFailureCode(String responseMode) {
        return switch (responseMode) {
            case "TIMEOUT" -> "PROVIDER_TIMEOUT";
            case "RATE_LIMITED" -> "PROVIDER_RATE_LIMITED";
            case "UNAVAILABLE" -> "PROVIDER_UNAVAILABLE";
            default -> "PROVIDER_MALFORMED_RESPONSE";
        };
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable.", exception);
        }
    }

    private record RecordedRow(
        int pageNumber,
        String responseMode,
        String payload,
        OffsetDateTime sourceUpdatedAt
    ) {
    }
}
