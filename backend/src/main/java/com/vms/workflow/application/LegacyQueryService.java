package com.vms.workflow.application;

import com.vms.workflow.security.TenantAuthorizationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class LegacyQueryService {
    private static final Map<String, String> TABLES = Map.of(
        "engagements", "legacy_engagements",
        "requirements", "legacy_requirements",
        "approvals", "legacy_approvals",
        "uat-items", "legacy_uat_items",
        "invoices", "legacy_invoices"
    );

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TenantAuthorizationService authorization;

    public LegacyQueryService(JdbcTemplate jdbc, ObjectMapper objectMapper,
                              TenantAuthorizationService authorization) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.authorization = authorization;
    }

    public List<JsonNode> find(String subject, UUID organizationId, String collection) {
        String table = TABLES.get(collection);
        if (table == null) {
            throw new IllegalArgumentException("Unknown legacy collection.");
        }
        List<UUID> organizationIds;
        if (organizationId == null) {
            organizationIds = authorization.organizationMemberships(subject).stream()
                .map(membership -> membership.getOrganization().getId())
                .distinct()
                .toList();
        } else {
            authorization.requireOrganization(subject, organizationId);
            organizationIds = List.of(organizationId);
        }
        return organizationIds.stream()
            .flatMap(id -> findForOrganization(table, id).stream())
            .toList();
    }

    private List<JsonNode> findForOrganization(String table, UUID organizationId) {
        return jdbc.query(
            "SELECT id, organization_id, legacy_key, payload::text, imported_at FROM " + table
                + " WHERE organization_id = ? ORDER BY legacy_key",
            (rs, rowNum) -> flatten(
                rs.getObject("id", UUID.class), readJson(rs.getString("payload"))),
            organizationId
        );
    }

    private JsonNode flatten(UUID id, JsonNode payload) {
        ObjectNode result = payload.isObject() ? ((ObjectNode) payload).deepCopy() : objectMapper.createObjectNode();
        result.put("id", id.toString());
        if (!payload.isObject()) {
            result.set("value", payload);
        }
        return result;
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored legacy JSON is invalid.", exception);
        }
    }
}
