package com.vms.workflow.application;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class FinanceCanonicalJsonTest {
    private final FinanceCanonicalJson canonical =
        new FinanceCanonicalJson(new ObjectMapper());

    @Test
    void mapOrderDoesNotChangeCanonicalBytesOrSha256() {
        UUID source = UUID.fromString(
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("schema", "f05-evidence-manifest-v1");
        first.put("sourceId", source);
        first.put("version", 4);
        first.put("items", List.of(Map.of("id", "a", "checksum", "0123")));

        Map<String, Object> second = new LinkedHashMap<>();
        second.put("items", List.of(Map.of("checksum", "0123", "id", "a")));
        second.put("version", 4);
        second.put("sourceId", source);
        second.put("schema", "f05-evidence-manifest-v1");

        assertEquals(canonical.write(first), canonical.write(second));
        assertEquals(canonical.sha256(first), canonical.sha256(second));
    }

    @Test
    void includedVersionOrSourceTimestampChangesTheHash() {
        Map<String, Object> first = Map.of(
            "sourceVersion", 1,
            "representedAt", OffsetDateTime.parse("2026-06-30T18:30:00Z"));
        Map<String, Object> changedVersion = Map.of(
            "sourceVersion", 2,
            "representedAt", OffsetDateTime.parse("2026-06-30T18:30:00Z"));
        Map<String, Object> changedTime = Map.of(
            "sourceVersion", 1,
            "representedAt", OffsetDateTime.parse("2026-06-30T18:31:00Z"));

        assertNotEquals(
            canonical.sha256(first), canonical.sha256(changedVersion));
        assertNotEquals(
            canonical.sha256(first), canonical.sha256(changedTime));
    }
}
