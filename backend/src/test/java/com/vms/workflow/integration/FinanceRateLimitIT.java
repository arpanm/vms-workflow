package com.vms.workflow.integration;

import com.vms.workflow.application.CanonicalEvidenceHasher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static com.vms.workflow.integration.F04TestSupport.token;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:tc:vmspostgresql:18-alpine:///vms_workflow_finance_rate_limit_it",
    "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.flyway.locations=classpath:db/migration,classpath:db/testdata",
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:9/test-jwks",
    "vms.security.issuer=https://issuer.example.test",
    "vms.security.audience=vms-api",
    "vms.finance.rate-limit.mutations-per-minute=2",
    "vms.finance.worker-initial-delay=PT1H"
})
@AutoConfigureMockMvc
class FinanceRateLimitIT {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CanonicalEvidenceHasher canonical;

    @Test
    void mutationLimitIsScopedPersistedAuditedAndFailsClosed()
        throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/api/v1/finance/invoices")
                    .with(token("user-arrow"))
                    .with(request -> {
                        request.setRemoteAddr("198.51.100.44");
                        return request;
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                .andExpect(status().isBadRequest());
        }

        mvc.perform(post("/api/v1/finance/invoices")
                .with(token("user-arrow"))
                .with(request -> {
                    request.setRemoteAddr("198.51.100.44");
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().string("Retry-After", "60"))
            .andExpect(jsonPath("$.status").value(429));

        String actorHash = canonical.sha256("user-arrow");
        String clientHash = canonical.sha256("198.51.100.44");
        Integer requestCount = jdbc.queryForObject("""
            SELECT request_count
            FROM f05_rate_limit_buckets
            WHERE operation = 'FINANCE_MUTATION'
              AND actor_subject_hash = ?
              AND client_address_hash = ?
            """, Integer.class, actorHash, clientHash);
        assertEquals(3, requestCount);
        assertEquals(1, jdbc.queryForObject("""
            SELECT count(*)
            FROM f05_security_events
            WHERE event_type = 'F05_RATE_LIMIT_EXCEEDED'
              AND result = 'DENIED'
              AND reason_code = 'RATE_LIMIT_EXCEEDED'
              AND actor_subject_hash = ?
            """, Integer.class, actorHash));
    }
}
