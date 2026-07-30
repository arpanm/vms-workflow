package com.vms.workflow.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static com.vms.workflow.integration.F04TestSupport.MONTH;
import static com.vms.workflow.integration.F04TestSupport.token;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:tc:vmspostgresql:18-alpine:///vms_workflow_certification_policy_it",
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
@Transactional
class CertificationPolicyIT {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void policyVersionsSupersedeOneWayAndKeepImmutableCapturedContent()
        throws Exception {
        String firstBody = policyBody(0, "ALL", 17, 86_400);
        String firstResponse = mvc.perform(post(
                    "/api/v1/certification/months/{monthId}/policy-versions",
                    MONTH)
                .with(token("user-governance"))
                .header("If-Match", "0")
                .header("Idempotency-Key", "policy-version-one")
                .contentType(MediaType.APPLICATION_JSON)
                .content(firstBody))
            .andExpect(status().isCreated())
            .andExpect(header().string("ETag", "\"1\""))
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.quorumMode").value("ALL"))
            .andExpect(jsonPath("$.quorumRequired").value(17))
            .andReturn().getResponse().getContentAsString();
        UUID firstId = UUID.fromString(
            mapper.readTree(firstResponse).path("id").asText());

        String secondResponse = mvc.perform(post(
                    "/api/v1/certification/months/{monthId}/policy-versions",
                    MONTH)
                .with(token("user-governance"))
                .header("If-Match", "1")
                .header("Idempotency-Key", "policy-version-two")
                .contentType(MediaType.APPLICATION_JSON)
                .content(policyBody(1, "ANY_ONE", 1, 172_800)))
            .andExpect(status().isCreated())
            .andExpect(header().string("ETag", "\"2\""))
            .andExpect(jsonPath("$.version").value(2))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andReturn().getResponse().getContentAsString();
        UUID secondId = UUID.fromString(
            mapper.readTree(secondResponse).path("id").asText());
        assertEquals("SUPERSEDED", jdbc.queryForObject("""
            SELECT status FROM certification_policy_versions WHERE id = ?
            """, String.class, firstId));
        assertEquals("ACTIVE", jdbc.queryForObject("""
            SELECT status FROM certification_policy_versions WHERE id = ?
            """, String.class, secondId));

        mvc.perform(post(
                    "/api/v1/certification/months/{monthId}/policy-versions",
                    MONTH)
                .with(token("user-governance"))
                .header("If-Match", "0")
                .header("Idempotency-Key", "policy-version-one")
                .contentType(MediaType.APPLICATION_JSON)
                .content(firstBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(firstId.toString()));
        assertThrows(DataAccessException.class, () -> jdbc.update("""
            UPDATE certification_policy_versions
            SET quorum_required = 1
            WHERE id = ?
            """, firstId));
    }

    @Test
    void exactScopedEvidenceExceptionMakesMandatoryCriterionSubmittable()
        throws Exception {
        F04TestSupport.FrozenBaseline baseline =
            F04TestSupport.frozenBaseline(mvc, mapper);
        JsonNode initial = F04TestSupport.workspace(
            mvc, mapper, "user-arrow");
        JsonNode draft = F04TestSupport.saveCompleteDraft(
            mvc, mapper, baseline, "user-arrow",
            initial.path("version").asLong(),
            "policy-missing-evidence-draft", false);
        UUID submissionId = UUID.fromString(
            draft.path("submission").path("id").asText());
        assertTrue(draft.path("submission").path("completenessBlockers")
            .toString().contains("EVIDENCE"));
        long monthVersion = draft.path("version").asLong();

        String exceptionResponse = mvc.perform(post(
                    "/api/v1/certification/submissions/{id}/evidence-exceptions",
                    submissionId)
                .with(token("user-governance"))
                .header("If-Match", Long.toString(monthVersion))
                .header("Idempotency-Key", "criterion-evidence-exception")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedMonthVersion":%d,
                      "deliverableId":"%s",
                      "criterionId":"%s",
                      "reasonCode":"CLIENT_WAIVER",
                      "justification":"Client governance approved an exact-scope evidence exception."
                    }
                    """.formatted(
                        monthVersion, baseline.deliverableVersionId(),
                        baseline.criterionId())))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.submissionId")
                .value(submissionId.toString()))
            .andExpect(jsonPath("$.criterionId")
                .value(baseline.criterionId().toString()))
            .andReturn().getResponse().getContentAsString();
        UUID exceptionId = UUID.fromString(
            mapper.readTree(exceptionResponse).path("id").asText());

        JsonNode submitted = F04TestSupport.submit(
            mvc, mapper, submissionId, 1,
            "submit-with-exact-evidence-exception");
        assertEquals(
            "UNDER_REVIEW",
            submitted.path("submission").path("status").asText());
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM certification_evidence_exceptions
            WHERE id = ? AND submission_id = ?
              AND deliverable_version_id = ? AND criterion_id = ?
            """, Integer.class, exceptionId, submissionId,
            baseline.deliverableVersionId(), baseline.criterionId()));
    }

    @Test
    void attendanceExceptionIsPolicyScopedAndMaterialToReadinessHash()
        throws Exception {
        F04TestSupport.frozenBaseline(mvc, mapper);
        mvc.perform(post(
                    "/api/v1/certification/months/{monthId}/policy-versions",
                    MONTH)
                .with(token("user-governance"))
                .header("If-Match", "0")
                .header("Idempotency-Key", "attendance-policy")
                .contentType(MediaType.APPLICATION_JSON)
                .content(policyBody(0, "ANY_ONE", 1, 86_400)))
            .andExpect(status().isCreated());
        long version = 1;
        String response = mvc.perform(post(
                    "/api/v1/certification/months/{monthId}/attendance-exceptions",
                    MONTH)
                .with(token("user-governance"))
                .header("If-Match", Long.toString(version))
                .header("Idempotency-Key", "attendance-exception")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedMonthVersion":%d,
                      "reasonCode":"SOURCE_UNAVAILABLE",
                      "justification":"Attendance source outage was reviewed and disclosed.",
                      "disclosures":["Source outage","Zero-day historical scope"]
                    }
                    """.formatted(version)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.reasonCode").value("SOURCE_UNAVAILABLE"))
            .andExpect(jsonPath("$.disclosures.length()").value(2))
            .andReturn().getResponse().getContentAsString();
        UUID exceptionId = UUID.fromString(
            mapper.readTree(response).path("id").asText());

        JsonNode readiness = mapper.readTree(mvc.perform(get(
                    "/api/v1/certification/months/{monthId}/readiness", MONTH)
                .with(token("user-governance")))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        JsonNode attendance = readiness.path("pillars").valueStream()
            .filter(value -> "ATTENDANCE".equals(
                value.path("key").asText()))
            .findFirst().orElseThrow();
        assertEquals("READY", attendance.path("status").asText());
        assertEquals(exceptionId.toString(),
            attendance.path("sourceVersionId").asText());
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT input_manifest::text LIKE '%' || ? || '%'
            FROM certification_readiness_runs
            WHERE engagement_month_id = ?::uuid
            ORDER BY evaluated_at DESC LIMIT 1
            """, Boolean.class, exceptionId.toString(), MONTH)));
    }

    private String policyBody(
        long expectedVersion,
        String quorumMode,
        int quorumRequired,
        int dueSeconds
    ) {
        return """
            {
              "expectedMonthVersion":%d,
              "attendanceRequired":true,
              "separationOfDutiesRequired":true,
              "monthlyDecisionRequired":true,
              "manualSecondReviewRequired":true,
              "quorumMode":"%s",
              "quorumRequired":%d,
              "tokenTtlSeconds":3600,
              "confirmationDueSeconds":%d,
              "reminderOffsetsSeconds":[3600,7200],
              "reviewSlaSeconds":3600,
              "evidenceRequiredWhenFrozenExpectationPresent":true,
              "allowedScanStatuses":["PASSED","NOT_REQUIRED"],
              "recipientSource":"FROZEN_PLAN_RECIPIENT_SNAPSHOT",
              "retentionDays":365
            }
            """.formatted(
                expectedVersion, quorumMode, quorumRequired, dueSeconds);
    }
}
