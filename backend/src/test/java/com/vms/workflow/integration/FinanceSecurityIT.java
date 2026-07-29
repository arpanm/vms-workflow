package com.vms.workflow.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static com.vms.workflow.integration.F04TestSupport.MONTH;
import static com.vms.workflow.integration.F04TestSupport.token;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
class FinanceSecurityIT {
    @Autowired
    private MockMvc mvc;

    @Test
    void unauthenticatedFinanceRequestUsesSafeProblemDetails() throws Exception {
        mvc.perform(get("/api/v1/finance/months"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith(
                MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(401))
            .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void wrongRoleAndCrossTenantMonthAreDeniedWithoutObjectContent()
        throws Exception {
        mvc.perform(get("/api/v1/finance/months")
                .with(token("user-wrong-role")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404));

        mvc.perform(get("/api/v1/finance/months/{monthId}", MONTH)
                .with(token("user-northstar")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.status").value(403))
            .andExpect(jsonPath("$.detail").value(
                "The authenticated identity is not authorized for this resource."));
    }

    @Test
    void accessCapabilitiesAreDerivedFromTheActiveScopedRole()
        throws Exception {
        mvc.perform(get("/api/v1/finance/access")
                .with(token("user-arrow")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.permissions").isArray())
            .andExpect(jsonPath("$.permissions[?(@ == 'INVOICE_CREATE')]")
                .exists())
            .andExpect(jsonPath("$.permissions[?(@ == 'INVOICE_SUBMIT')]")
                .exists())
            .andExpect(jsonPath("$.permissions[?(@ == 'PROCUREMENT_REVIEW')]")
                .doesNotExist())
            .andExpect(jsonPath("$.permissions[?(@ == 'PAYMENT_UPDATE')]")
                .doesNotExist());

        mvc.perform(get("/api/v1/finance/access")
                .with(token("user-procurement")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.permissions[?(@ == 'PROCUREMENT_REVIEW')]")
                .exists())
            .andExpect(jsonPath("$.permissions[?(@ == 'PROCUREMENT_QUERY')]")
                .exists())
            .andExpect(jsonPath("$.permissions[?(@ == 'PAYMENT_UPDATE')]")
                .doesNotExist());

        mvc.perform(get("/api/v1/finance/access")
                .with(token("user-finance-ap")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.permissions[?(@ == 'PAYMENT_UPDATE')]")
                .exists())
            .andExpect(jsonPath("$.permissions[?(@ == 'PROCUREMENT_REVIEW')]")
                .doesNotExist());
    }

    @Test
    void reportCatalogPublishesTheVersionedExportContract()
        throws Exception {
        mvc.perform(get("/api/v1/finance/reports")
            .with(token("user-procurement")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.definitions.length()").value(8))
            .andExpect(jsonPath("$.definitions[5].reportId")
                .value("INVOICE_READINESS"))
            .andExpect(jsonPath("$.definitions[5].version")
                .value("v1"))
            .andExpect(jsonPath("$.definitions[5].availableFormats.length()")
                .value(4))
            .andExpect(jsonPath("$.permissions").isArray())
            .andExpect(jsonPath("$.exports.items").isArray());
    }
}
