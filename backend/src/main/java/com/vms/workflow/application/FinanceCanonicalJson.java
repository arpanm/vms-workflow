package com.vms.workflow.application;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Canonical JSON and hashing boundary for F05 immutable manifests.
 *
 * <p>Map keys and object properties are sorted before hashing. Arrays deliberately
 * retain their order because package item ordinal is part of the evidence contract.</p>
 */
@Component
public class FinanceCanonicalJson {
    private final ObjectMapper canonicalMapper;

    public FinanceCanonicalJson(ObjectMapper objectMapper) {
        this.canonicalMapper = objectMapper;
    }

    public String write(Object value) {
        try {
            JsonNode tree = canonicalMapper.valueToTree(value);
            return canonicalMapper.writeValueAsString(sort(tree));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to canonicalize finance evidence.", exception);
        }
    }

    public String sha256(Object value) {
        return sha256Text(write(value));
    }

    public String sha256Text(String value) {
        return sha256Bytes(value.getBytes(StandardCharsets.UTF_8));
    }

    public String sha256Bytes(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    public Map<String, Object> readMap(String value) {
        try {
            return canonicalMapper.readValue(
                value, new tools.jackson.core.type.TypeReference<>() { });
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                "Unable to read canonical finance evidence.", exception);
        }
    }

    private JsonNode sort(JsonNode node) {
        if (node.isObject()) {
            var result = canonicalMapper.createObjectNode();
            node.properties().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> result.set(entry.getKey(), sort(entry.getValue())));
            return result;
        }
        if (node.isArray()) {
            var result = canonicalMapper.createArrayNode();
            java.util.List<JsonNode> children = new java.util.ArrayList<>();
            node.forEach(child -> children.add(sort(child)));
            if (children.stream().allMatch(JsonNode::isObject)) {
                children.sort(java.util.Comparator.comparing(this::arraySortKey));
            }
            children.forEach(result::add);
            return result;
        }
        return node;
    }

    private String arraySortKey(JsonNode node) {
        for (String key : java.util.List.of(
                "ordinal", "logicalType", "ruleId", "pillar", "id",
                "sourceObjectId", "itemId")) {
            JsonNode candidate = node.get(key);
            if (candidate != null && !candidate.isNull()) {
                return key + ":" + candidate.asText() + ":" + node;
            }
        }
        return node.toString();
    }
}
