package com.vms.workflow.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.CallableStatementCallback;
import org.springframework.jdbc.core.CallableStatementCreator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.StatementCallback;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.vms.workflow.integration.F04TestSupport.ENGAGEMENT;
import static com.vms.workflow.integration.F04TestSupport.PROJECT_A;
import static com.vms.workflow.integration.F04TestSupport.token;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:tc:vmspostgresql:18-alpine:///vms_workflow_f04_scale",
    "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.flyway.locations=classpath:db/migration,classpath:db/testdata",
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:9/test-jwks",
    "vms.security.issuer=https://issuer.example.test",
    "vms.security.audience=vms-api",
    "vms.certification.worker-initial-delay=PT1H"
})
@AutoConfigureMockMvc
@Import(CertificationWorkspaceScaleIT.QueryCountingConfiguration.class)
@Transactional
class CertificationWorkspaceScaleIT {
    private static final String ONE_MONTH =
        "00000000-0000-0000-0000-000000000606";
    private static final String MANY_MONTH =
        "00000000-0000-0000-0000-000000000607";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private CountingJdbcTemplate jdbc;

    @Test
    void workspaceQueryCountDoesNotGrowWithDeliverableCount()
        throws Exception {
        createFrozenPlan(ONE_MONTH, "2026-08-01", "2026-08-31", 1);
        createFrozenPlan(MANY_MONTH, "2026-09-01", "2026-09-30", 12);

        int oneQueries = workspaceQueries(ONE_MONTH, 1);
        int manyQueries = workspaceQueries(MANY_MONTH, 12);

        assertTrue(
            manyQueries <= oneQueries + 2,
            () -> "Bulk hydration query count grew with deliverables: one="
                + oneQueries + ", twelve=" + manyQueries);
    }

    private int workspaceQueries(String monthId, int deliverables)
        throws Exception {
        jdbc.reset();
        mvc.perform(get("/api/v1/certification/months/{monthId}", monthId)
                .with(token("user-reliance")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.deliverables.length()").value(deliverables));
        return jdbc.count();
    }

    private void createFrozenPlan(
        String monthId,
        String monthStart,
        String monthEnd,
        int deliverableCount
    ) throws Exception {
        jdbc.update("""
            INSERT INTO engagement_months
                (id, engagement_id, month_start_date, state, risk_status)
            VALUES (?::uuid, ?::uuid, ?::date, 'ACTIVE', 'ON_TRACK')
            ON CONFLICT DO NOTHING
            """, monthId, ENGAGEMENT, monthStart);
        List<Map<String, Object>> deliverables = new ArrayList<>();
        for (int index = 1; index <= deliverableCount; index++) {
            Map<String, Object> deliverable = new LinkedHashMap<>();
            deliverable.put(
                "deliverableCode", "SCALE-" + deliverableCount + "-" + index);
            deliverable.put("title", "Scale deliverable " + index);
            deliverable.put(
                "description", "Bounded workspace hydration fixture " + index);
            deliverable.put(
                "businessObjective", "Keep query count independent of row count");
            deliverable.put("projectId", PROJECT_A);
            deliverable.put("productOwnerSubject", "user-reliance");
            deliverable.put("vendorOwnerSubject", "user-arrow");
            deliverable.put("priority", "P1");
            deliverable.put("targetCompletionDate", monthEnd);
            deliverable.put(
                "evidenceExpectations", "One safe scale-test evidence reference");
            deliverable.put("dependencyNoneDeclared", true);
            deliverable.put(
                "riskAndAssumptions", "Local PostgreSQL query-count fixture");
            deliverable.put("deliveryCategory", "QUALITY");
            deliverable.put(
                "linkExceptionReason",
                "Approved provider fixture exception for bounded local test");
            deliverable.put("criteria", List.of(Map.of(
                "statement", "Workspace row " + index + " is hydrated",
                "validationMethod", "Bulk-query integration test",
                "expectedResult", "No per-row JDBC fan-out",
                "mandatory", true)));
            deliverable.put("dependencies", List.of());
            deliverable.put("assignments", List.of(Map.of(
                "employeeId", "00000000-0000-0000-0000-000000000801",
                "effectiveFrom", monthStart,
                "effectiveTo", monthEnd)));
            deliverables.add(deliverable);
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("engagementMonthId", monthId);
        request.put("title", "Scale plan " + deliverableCount);
        request.put("summary", "Bounded certification workspace hydration");
        request.put("businessOutcomes", "Query count remains constant");
        request.put("coordinatorSubject", "user-arrow");
        request.put("baselineType", "ON_TIME");
        request.put("quorumMode", "ANY_ONE");
        request.put("quorumRequired", 1);
        request.put("approverSubjects", List.of("user-approver"));
        request.put("recipients", Map.of(
            "arrowFoundry", List.of("vendor@example.test"),
            "relianceStakeholders", List.of("owner@example.test"),
            "procurementCc", List.of("procurement@example.test")));
        request.put("deliverables", deliverables);

        JsonNode created = mapper.readTree(mvc.perform(post(
                    "/api/v1/delivery/plans")
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString());
        String planId = created.path("id").asText();
        mvc.perform(post("/api/v1/delivery/plans/{planId}/submit", planId)
                .with(token("user-arrow")))
            .andExpect(status().isOk());
        mvc.perform(post("/api/v1/delivery/plans/{planId}/approvals", planId)
                .with(token("user-approver"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVE\"}"))
            .andExpect(status().isOk());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class QueryCountingConfiguration {
        @Bean
        @Primary
        CountingJdbcTemplate countingJdbcTemplate(DataSource dataSource) {
            return new CountingJdbcTemplate(dataSource);
        }
    }

    static final class CountingJdbcTemplate extends JdbcTemplate {
        private final AtomicInteger executions = new AtomicInteger();

        private CountingJdbcTemplate(DataSource dataSource) {
            super(dataSource);
        }

        void reset() {
            executions.set(0);
        }

        int count() {
            return executions.get();
        }

        @Override
        public <T> T execute(
            PreparedStatementCreator creator,
            PreparedStatementCallback<T> action
        ) throws DataAccessException {
            executions.incrementAndGet();
            return super.execute(creator, action);
        }

        @Override
        public <T> T execute(StatementCallback<T> action)
            throws DataAccessException {
            executions.incrementAndGet();
            return super.execute(action);
        }

        @Override
        public <T> T execute(
            CallableStatementCreator creator,
            CallableStatementCallback<T> action
        ) throws DataAccessException {
            executions.incrementAndGet();
            return super.execute(creator, action);
        }
    }
}
