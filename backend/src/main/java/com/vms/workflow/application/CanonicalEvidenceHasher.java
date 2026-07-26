package com.vms.workflow.application;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.Set;

@Component
public class CanonicalEvidenceHasher {
    public static final String ALGORITHM = "SHA-256";
    public static final int SCHEMA_VERSION = 1;
    private static final Set<String> SET_LIKE_FIELDS = Set.of(
        "actionIds",
        "allowedScanStatuses",
        "criterionResults",
        "criterionResponses",
        "disclosures",
        "evidenceReferenceIds",
        "impactedRecordIds",
        "items",
        "recipients",
        "reminderOffsetsSeconds");

    private final ObjectMapper objectMapper;

    public CanonicalEvidenceHasher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public HashResult hash(Map<String, ?> manifest) {
        Object normalized = normalize(manifest, null);
        try {
            String canonical = objectMapper.writeValueAsString(normalized);
            return new HashResult(
                canonical, sha256(canonical), ALGORITHM, SCHEMA_VERSION);
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                "Unable to canonicalize certification evidence.", exception);
        }
    }

    public String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance(ALGORITHM)
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private Object normalize(Object value, String fieldName) {
        if (value == null
            || value instanceof String
            || value instanceof Number
            || value instanceof Boolean) {
            return value;
        }
        if (value instanceof UUID uuid) {
            return uuid.toString();
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value instanceof OffsetDateTime timestamp) {
            return timestamp.withOffsetSameInstant(ZoneOffset.UTC).toInstant().toString();
        }
        if (value instanceof Instant instant) {
            return instant.toString();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, child) ->
                sorted.put(
                    String.valueOf(key),
                    normalize(child, String.valueOf(key))));
            return sorted;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> normalized = new ArrayList<>();
            iterable.forEach(child ->
                normalized.add(normalize(child, null)));
            if (SET_LIKE_FIELDS.contains(fieldName)) {
                normalized.sort(Comparator.comparing(this::stableSortKey));
            }
            return normalized;
        }
        if (value.getClass().isArray()) {
            List<Object> normalized = new ArrayList<>();
            for (Object child : (Object[]) value) {
                normalized.add(normalize(child, null));
            }
            if (SET_LIKE_FIELDS.contains(fieldName)) {
                normalized.sort(Comparator.comparing(this::stableSortKey));
            }
            return normalized;
        }
        Map<String, Object> converted = objectMapper.convertValue(
            value, new tools.jackson.core.type.TypeReference<>() {
            });
        return normalize(converted, fieldName);
    }

    private String stableSortKey(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (String key : List.of(
                    "id", "actionId", "criterionId", "deliverableId",
                    "artifactId")) {
                Object candidate = map.get(key);
                if (candidate != null) {
                    return key + ":" + candidate;
                }
            }
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                "Unable to sort canonical set-like evidence.", exception);
        }
    }

    public Map<String, Object> sortedManifest(List<Map<String, Object>> items) {
        List<Map<String, Object>> sorted = items.stream()
            .sorted(Comparator.comparing(item -> String.valueOf(item.get("id"))))
            .toList();
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("hashSchemaVersion", SCHEMA_VERSION);
        manifest.put("items", sorted);
        return manifest;
    }

    public record HashResult(
        String canonicalJson,
        String checksum,
        String algorithm,
        int schemaVersion
    ) {
    }
}
