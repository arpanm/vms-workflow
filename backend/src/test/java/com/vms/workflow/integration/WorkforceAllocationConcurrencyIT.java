package com.vms.workflow.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:tc:vmspostgresql:18-alpine:///vms_workflow_workforce_allocation_concurrency_it",
    "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.flyway.locations=classpath:db/migration,classpath:db/testdata",
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:9/test-jwks",
    "vms.security.issuer=https://issuer.example.test",
    "vms.security.audience=vms-api"
})
@AutoConfigureMockMvc
class WorkforceAllocationConcurrencyIT {
    private static final String EMPLOYEE = "00000000-0000-0000-0000-000000000801";
    private static final String ENGAGEMENT = "00000000-0000-0000-0000-000000000401";
    private static final String PROJECT = "00000000-0000-0000-0000-000000000502";

    @Autowired
    private MockMvc mvc;

    @Test
    void twoSessionsCannotCommitAnOverOneHundredPercentSum() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var calls = List.of(1, 2).stream().map(index -> executor.submit(() -> {
                ready.countDown();
                start.await();
                return mvc.perform(post("/api/v1/workforce/employees/{id}/allocations", EMPLOYEE)
                        .with(jwt().jwt(value -> value.subject("user-arrow")
                            .audience(List.of("vms-api"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "engagementId":"%s",
                              "projectId":"%s",
                              "validFrom":"2029-01-01",
                              "validTo":"2029-01-31",
                              "allocationPercent":30,
                              "roleOnProject":"Concurrent %s"
                            }
                            """.formatted(ENGAGEMENT, PROJECT, index)))
                    .andReturn().getResponse().getStatus();
            })).toList();
            ready.await();
            start.countDown();
            List<Integer> statuses = calls.stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }).sorted().toList();
            assertEquals(List.of(201, 409), statuses);
        }
    }
}
