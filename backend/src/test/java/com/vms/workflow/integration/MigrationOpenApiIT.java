package com.vms.workflow.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static com.vms.workflow.integration.F04TestSupport.token;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:tc:vmspostgresql:18-alpine:///vms_workflow_migration_open_api_it",
    "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.flyway.locations=classpath:db/migration,classpath:db/testdata",
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:9/test-jwks",
    "vms.security.issuer=https://issuer.example.test",
    "vms.security.audience=vms-api",
    "vms.finance.cursor-signing-secret="
        + "migration-openapi-secret-with-at-least-32-bytes"
})
@AutoConfigureMockMvc
class MigrationOpenApiIT {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void authenticatedDocumentPublishesEveryGovernedMigrationOperation()
        throws Exception {
        mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isUnauthorized());
        JsonNode paths = docs().path("paths");
        List<String> routes = List.of(
            "/api/v1/migrations/access",
            "/api/v1/migrations/templates",
            "/api/v1/migrations/templates/{templateCode}/download",
            "/api/v1/migrations/jobs",
            "/api/v1/migrations/jobs/{jobId}",
            "/api/v1/migrations/jobs/{jobId}/rows",
            "/api/v1/migrations/jobs/{jobId}/correction-plan",
            "/api/v1/migrations/jobs/{jobId}/validate",
            "/api/v1/migrations/jobs/{jobId}/validation-runs",
            "/api/v1/migrations/jobs/{jobId}/rows/{rowId}/resolution",
            "/api/v1/migrations/jobs/{jobId}/approvals",
            "/api/v1/migrations/jobs/{jobId}/commit",
            "/api/v1/migrations/jobs/{jobId}/reprocess",
            "/api/v1/migrations/jobs/{jobId}/retry",
            "/api/v1/migrations/jobs/{jobId}/cancel",
            "/api/v1/migrations/jobs/{jobId}/rollback",
            "/api/v1/migrations/jobs/{jobId}/errors/download",
            "/api/v1/migrations/jobs/{jobId}/reconciliation",
            "/api/v1/migrations/jobs/{jobId}/audit",
            "/api/v1/migrations/reconciliations/{reportId}/sign-offs",
            "/api/v1/migrations/retro-requests",
            "/api/v1/migrations/retro-requests/{requestId}/decision",
            "/api/v1/migrations/retro-requests/{requestId}/cancel",
            "/api/v1/migrations/months/{monthId}/readiness",
            "/api/v1/migrations/months/{monthId}/transitions");
        assertAll(routes.stream().map(route -> () ->
            assertFalse(paths.path(route).isMissingNode(),
                route + " must be published")));
    }

    @Test
    void mutationSchemasAndRequiredConcurrencyHeadersMatchController()
        throws Exception {
        JsonNode api = docs();
        JsonNode paths = api.path("paths");
        List<String> versioned = List.of(
            "/api/v1/migrations/jobs/{jobId}/validate",
            "/api/v1/migrations/jobs/{jobId}/validation-runs",
            "/api/v1/migrations/jobs/{jobId}/rows/{rowId}/resolution",
            "/api/v1/migrations/jobs/{jobId}/approvals",
            "/api/v1/migrations/jobs/{jobId}/commit",
            "/api/v1/migrations/jobs/{jobId}/reprocess",
            "/api/v1/migrations/jobs/{jobId}/retry",
            "/api/v1/migrations/jobs/{jobId}/cancel",
            "/api/v1/migrations/jobs/{jobId}/rollback",
            "/api/v1/migrations/retro-requests/{requestId}/decision",
            "/api/v1/migrations/retro-requests/{requestId}/cancel",
            "/api/v1/migrations/months/{monthId}/transitions");
        assertAll(versioned.stream().map(route -> () -> {
            JsonNode operation = paths.path(route).path("post");
            assertTrue(hasHeader(operation, "If-Match"),
                route + " must require If-Match");
            assertTrue(hasHeader(operation, "Idempotency-Key"),
                route + " must require Idempotency-Key");
        }));
        JsonNode schemas = api.path("components").path("schemas");
        JsonNode upload = schemas.path("UploadMetadata").path("properties");
        assertTrue(upload.has("sourceType"));
        assertTrue(upload.has("confidence"));
        assertTrue(upload.has("sourceDescription"));
        assertTrue(schemas.path("RetroDecisionInput").path("properties")
            .has("decision"));
        assertTrue(schemas.path("MonthTransitionInput").path("properties")
            .has("targetState"));
        String serialized = schemas.toString();
        assertFalse(serialized.contains("migration_source_blobs"));
        assertFalse(serialized.contains("normalized_payload"));
    }

    @Test
    void runtimeResponsePreservesNormalizedRequestCorrelation()
        throws Exception {
        UUID correlation = UUID.fromString(
            "f0600000-0000-0000-0000-000000000041");
        mvc.perform(get("/api/v1/migrations/access")
                .header("X-Correlation-Id", correlation)
                .with(token("user-arrow")))
            .andExpect(status().isOk())
            .andExpect(header().string(
                "X-Correlation-Id", correlation.toString()));
    }

    private boolean hasHeader(JsonNode operation, String name) {
        for (JsonNode parameter : operation.path("parameters")) {
            if (name.equals(parameter.path("name").asText())
                && "header".equals(parameter.path("in").asText())) {
                return true;
            }
        }
        return false;
    }

    private JsonNode docs() throws Exception {
        return mapper.readTree(mvc.perform(get("/v3/api-docs")
                .with(token("user-arrow")))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
    }
}
