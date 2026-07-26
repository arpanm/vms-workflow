package com.vms.workflow.application;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class CanonicalEvidenceHasherTest {
    private final CanonicalEvidenceHasher hasher =
        new CanonicalEvidenceHasher(JsonMapper.builder().build());

    @Test
    void setLikeApiCollectionsAreOrderIndependent() {
        UUID first = UUID.fromString(
            "00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString(
            "00000000-0000-0000-0000-000000000002");
        String left = hasher.hash(Map.of(
            "impactedRecordIds", List.of(second, first),
            "criterionResults", List.of(
                Map.of("criterionId", second, "decision", "MET"),
                Map.of("criterionId", first, "decision", "MET"))))
            .checksum();
        String right = hasher.hash(Map.of(
            "criterionResults", List.of(
                Map.of("criterionId", first, "decision", "MET"),
                Map.of("criterionId", second, "decision", "MET")),
            "impactedRecordIds", List.of(first, second)))
            .checksum();

        assertEquals(left, right);
    }

    @Test
    void explicitlyOrderedCollectionsRetainOrder() {
        assertNotEquals(
            hasher.hash(Map.of("questions", List.of("first", "second")))
                .checksum(),
            hasher.hash(Map.of("questions", List.of("second", "first")))
                .checksum());
    }
}
