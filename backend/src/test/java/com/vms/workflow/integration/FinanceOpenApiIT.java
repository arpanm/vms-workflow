package com.vms.workflow.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static com.vms.workflow.integration.F04TestSupport.token;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class FinanceOpenApiIT {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void financeDocumentationIsAuthenticatedAndPublishesTheExecutableVertical()
        throws Exception {
        mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/v3/api-docs").with(token("user-arrow")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paths['/api/v1/finance/invoices']").exists())
            .andExpect(jsonPath(
                "$.paths['/api/v1/finance/invoices/{invoiceId}/documents']").exists())
            .andExpect(jsonPath(
                "$.paths['/api/v1/finance/invoices/{invoiceId}/readiness-runs']").exists())
            .andExpect(jsonPath(
                "$.paths['/api/v1/finance/months/{monthId}/packages']").exists())
            .andExpect(jsonPath(
                "$.paths['/api/v1/finance/procurement/invoices/{invoiceId}/reviews']").exists())
            .andExpect(jsonPath(
                "$.paths['/api/v1/finance/procurement/exceptions/{exceptionId}/second-approval']").exists())
            .andExpect(jsonPath(
                "$.paths['/api/v1/finance/invoices/{invoiceId}/payments']").exists())
            .andExpect(jsonPath(
                "$.paths['/api/v1/finance/procurement/control-tower']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/finance/exports']").exists());
    }

    @Test
    void consequentialFinanceOperationsDeclareConcurrencyAndIdempotencyHeaders()
        throws Exception {
        JsonNode paths = docs().path("paths");
        List<String> operations = List.of(
            "/api/v1/finance/invoices/{invoiceId}/documents",
            "/api/v1/finance/invoices/{invoiceId}/documents/replace",
            "/api/v1/finance/invoices/{invoiceId}/readiness-runs",
            "/api/v1/finance/invoices/{invoiceId}/submit",
            "/api/v1/finance/months/{monthId}/packages",
            "/api/v1/finance/procurement/invoices/{invoiceId}/reviews",
            "/api/v1/finance/procurement/invoices/{invoiceId}/queries",
            "/api/v1/finance/procurement/invoices/{invoiceId}/exceptions",
            "/api/v1/finance/procurement/exceptions/{exceptionId}/second-approval",
            "/api/v1/finance/invoices/{invoiceId}/payments");
        for (String path : operations) {
            JsonNode post = paths.path(path).path("post");
            assertFalse(post.isMissingNode(), path);
            assertTrue(hasHeader(post, "If-Match"), path);
            assertTrue(hasHeader(post, "Idempotency-Key"), path);
            assertTrue(hasHeader(post, "X-Correlation-Id"), path);
        }
        JsonNode export = paths.path("/api/v1/finance/exports").path("post");
        assertTrue(hasHeader(export, "Idempotency-Key"));
        assertTrue(hasHeader(export, "X-Correlation-Id"));
    }

    private JsonNode docs() throws Exception {
        String body = mvc.perform(get("/v3/api-docs")
                .with(token("user-arrow")))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body);
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
}
