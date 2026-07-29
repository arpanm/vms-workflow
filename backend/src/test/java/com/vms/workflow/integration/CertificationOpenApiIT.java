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

import static com.vms.workflow.integration.F04TestSupport.MONTH;
import static com.vms.workflow.integration.F04TestSupport.token;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:tc:vmspostgresql:18-alpine:///vms_workflow",
    "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.flyway.locations=classpath:db/migration,classpath:db/testdata",
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:9/test-jwks",
    "vms.security.issuer=https://issuer.example.test",
    "vms.security.audience=vms-api"
})
@AutoConfigureMockMvc
class CertificationOpenApiIT {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void documentationAudienceIsAuthenticatedAndF04OperationsArePublished()
        throws Exception {
        mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/v3/api-docs").with(token("user-reliance")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paths['/api/v1/certification/months/{monthId}']")
                .exists())
            .andExpect(jsonPath(
                "$.paths['/api/v1/certification/submissions/{submissionId}/submit']")
                .exists())
            .andExpect(jsonPath(
                "$.paths['/api/v1/certification/confirmation-requests/{requestId}/actions']")
                .exists());
    }

    @Test
    void secureTokenIsWriteOnlyAndRestrictedPersistenceFieldsAreNotResponseSchema()
        throws Exception {
        JsonNode api = docs();
        JsonNode actionInput = api.path("components").path("schemas")
            .path("ConfirmationActionRequest").path("properties");
        assertTrue(actionInput.path("secureToken").path("writeOnly").asBoolean());
        JsonNode inboundInput = api.path("components").path("schemas")
            .path("InboundMessageRecordInput").path("properties");
        JsonNode manualInput = api.path("components").path("schemas")
            .path("ManualEvidenceRecordInput").path("properties");
        assertTrue(inboundInput.path("senderAddress").path("writeOnly")
            .asBoolean());
        assertTrue(manualInput.path("senderAddress").path("writeOnly")
            .asBoolean());
        assertTrue(manualInput.path("recipients").path("writeOnly")
            .asBoolean());
        assertTrue(manualInput.path("subject").path("writeOnly")
            .asBoolean());
        String schemas = api.path("components").path("schemas").toString();
        assertFalse(schemas.contains("tokenHash"));
        assertFalse(schemas.contains("tokenSalt"));
        assertFalse(schemas.contains("rawMime"));
        assertFalse(schemas.contains("providerSecret"));
    }

    @Test
    void everyConsequentialF04OperationDocumentsEtagIdempotencyAndCorrelation()
        throws Exception {
        JsonNode paths = docs().path("paths");
        List<String> operations = List.of(
            "/api/v1/certification/months/{monthId}/submissions",
            "/api/v1/certification/submissions/{submissionId}/submit",
            "/api/v1/certification/submissions/{submissionId}/clarifications",
            "/api/v1/certification/submissions/{submissionId}/certifications",
            "/api/v1/certification/months/{monthId}/summaries",
            "/api/v1/certification/months/{monthId}/confirmation-requests",
            "/api/v1/certification/confirmation-requests/{requestId}/actions",
            "/api/v1/certification/confirmation-requests/{requestId}/governance-decisions",
            "/api/v1/certification/months/{monthId}/inbound-messages",
            "/api/v1/certification/inbound-messages/{messageId}/reviews",
            "/api/v1/certification/months/{monthId}/manual-evidence",
            "/api/v1/certification/manual-evidence/{evidenceId}/reviews",
            "/api/v1/certification/notifications/{notificationId}/replays",
            "/api/v1/certification/months/{monthId}/policy-versions",
            "/api/v1/certification/submissions/{submissionId}/evidence-exceptions",
            "/api/v1/certification/months/{monthId}/attendance-exceptions",
            "/api/v1/certification/months/{monthId}/reopen-requests",
            "/api/v1/certification/months/{monthId}/closures",
            "/api/v1/certification/reopen-requests/{reopenRequestId}/decisions",
            "/api/v1/certification/invalidations/{invalidationId}/resolutions");
        assertAll(operations.stream().map(path -> () -> {
            JsonNode operation = paths.path(path).path("post");
            assertFalse(operation.isMissingNode(), path + " must exist");
            assertTrue(hasHeaderParameter(operation, "If-Match"),
                path + " must document If-Match");
            assertTrue(hasHeaderParameter(operation, "Idempotency-Key"),
                path + " must document Idempotency-Key");
            assertTrue(hasHeaderParameter(operation, "X-Correlation-Id"),
                path + " must document request correlation");
            assertTrue(hasResponseHeader(operation, "X-Correlation-Id"),
                path + " must document response correlation");
        }));
    }

    @Test
    void runtimeSuccessAndTypedErrorPropagateOneNormalizedCorrelationId()
        throws Exception {
        UUID correlation = UUID.fromString(
            "c0400000-0000-0000-0000-000000000001");
        mvc.perform(get("/api/v1/certification/months/{monthId}", MONTH)
                .header("X-Correlation-Id", correlation)
                .with(token("user-reliance")))
            .andExpect(status().isOk())
            .andExpect(header().string(
                "X-Correlation-Id", correlation.toString()));

        String body = mvc.perform(get("/api/v1/certification/months/not-a-uuid")
                .header("X-Correlation-Id", correlation)
                .with(token("user-reliance")))
            .andExpect(status().isBadRequest())
            .andExpect(header().string(
                "X-Correlation-Id", correlation.toString()))
            .andReturn().getResponse().getContentAsString();
        assertEquals(
            correlation.toString(),
            mapper.readTree(body).path("correlationId").asText());
    }

    private boolean hasHeaderParameter(JsonNode operation, String name) {
        for (JsonNode parameter : operation.path("parameters")) {
            if (name.equals(parameter.path("name").asText())
                && "header".equals(parameter.path("in").asText())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasResponseHeader(JsonNode operation, String name) {
        for (JsonNode response : operation.path("responses")) {
            if (response.path("headers").has(name)) {
                return true;
            }
        }
        return false;
    }

    private JsonNode docs() throws Exception {
        return mapper.readTree(mvc.perform(get("/v3/api-docs")
                .with(token("user-reliance")))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
    }
}
