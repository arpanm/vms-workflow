package com.vms.workflow.integration;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:tc:vmspostgresql:18-alpine:///vms_workflow_f07_http_hardening_it",
    "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:9/test-jwks",
    "vms.security.issuer=https://issuer.example.test",
    "vms.security.audience=vms-api",
    "vms.security.max-json-request-bytes=64",
    "vms.security.cors.allowed-origins=https://app.example.test"
})
@AutoConfigureMockMvc
class F07HttpHardeningIT {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private MeterRegistry meters;

    @Test
    void livenessAndReadinessArePublicMinimalAndInfoIsUnavailable()
        throws Exception {
        mvc.perform(get("/actuator/health/liveness"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(content().string(not(containsString("database"))))
            .andExpect(content().string(not(containsString("jdbc"))))
            .andExpect(content().string(not(containsString("host"))));
        mvc.perform(get("/actuator/health/readiness"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(content().string(not(containsString("components"))));
        mvc.perform(get("/actuator/info"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void headersApplyToSecureHealthAndAuthenticationFailures()
        throws Exception {
        mvc.perform(get("/actuator/health").secure(true))
            .andExpect(status().isOk())
            .andExpect(header().string(
                "Strict-Transport-Security",
                "max-age=31536000; includeSubDomains"))
            .andExpect(header().string("X-Frame-Options", "DENY"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("Referrer-Policy", "no-referrer"))
            .andExpect(header().string(
                "Cache-Control",
                "no-store, no-cache, max-age=0, must-revalidate, private"));
        mvc.perform(get("/api/v1/me"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().exists("X-Correlation-Id"))
            .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    void corsAllowsOnlyConfiguredExactOrigin() throws Exception {
        mvc.perform(options("/api/v1/me")
                .header("Origin", "https://app.example.test")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "Authorization"))
            .andExpect(status().isOk())
            .andExpect(header().string(
                "Access-Control-Allow-Origin", "https://app.example.test"))
            .andExpect(header().doesNotExist(
                "Access-Control-Allow-Credentials"));

        mvc.perform(options("/api/v1/me")
                .header("Origin", "https://attacker.example")
                .header("Access-Control-Request-Method", "GET"))
            .andExpect(status().isForbidden())
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void rejectedOversizedRequestsRemainVisibleInApiTelemetry()
        throws Exception {
        io.micrometer.core.instrument.Counter existing = meters.find(
                "vms.api.request.outcomes")
            .tag("method", "POST")
            .tag("route", "UNMATCHED")
            .tag("status", "4xx")
            .tag("outcome", "client_error")
            .counter();
        double before = existing == null ? 0 : existing.count();
        mvc.perform(post("/api/v1/me")
                .contentType("application/json")
                .content("{\"payload\":\"" + "x".repeat(128) + "\"}"))
            .andExpect(status().isPayloadTooLarge());
        double after = meters.get("vms.api.request.outcomes")
            .tag("method", "POST")
            .tag("route", "UNMATCHED")
            .tag("status", "4xx")
            .tag("outcome", "client_error")
            .counter().count();
        org.junit.jupiter.api.Assertions.assertEquals(before + 1, after);
    }
}
