package com.vms.workflow.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static com.vms.workflow.integration.F04TestSupport.MONTH;
import static com.vms.workflow.integration.F04TestSupport.token;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    "vms.security.audience=vms-api",
    "vms.certification.email-provider-status=NOT_CONFIGURED",
    "vms.certification.object-storage-provider-status=NOT_CONFIGURED"
})
@AutoConfigureMockMvc
@Transactional
class CertificationWorkflowIT {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void frozenBaselineSubmissionCertificationSummaryAndReadinessRemainExplicit()
        throws Exception {
        F04TestSupport.FrozenBaseline baseline =
            F04TestSupport.frozenBaseline(mvc, mapper);
        String frozenTitle = jdbc.queryForObject("""
            SELECT title FROM delivery_plan_versions WHERE id = ?
            """, String.class, baseline.planVersionId());
        JsonNode workspace = F04TestSupport.workspace(mvc, mapper, "user-arrow");

        JsonNode draft = F04TestSupport.saveCompleteDraft(
            mvc, mapper, baseline, "user-arrow", workspace.path("version").asLong(),
            "vertical-save");
        UUID submissionId = UUID.fromString(draft.path("submission").path("id").asText());
        assertEquals(baseline.planVersionId(), jdbc.queryForObject("""
            SELECT plan_version_id FROM delivery_submissions WHERE id = ?
            """, UUID.class, submissionId));
        assertEquals(baseline.baselineId(), jdbc.queryForObject("""
            SELECT baseline_id FROM delivery_submissions WHERE id = ?
            """, UUID.class, submissionId));

        JsonNode submitted = F04TestSupport.submit(
            mvc, mapper, submissionId, 1, "vertical-submit");
        assertEquals("UNDER_REVIEW",
            submitted.path("submission").path("status").asText());
        assertTrue(submitted.path("submission").path("locked").asBoolean());
        assertEquals(1, count("""
            SELECT COUNT(*) FROM notification_outbox
            WHERE business_object_id = ? AND event_type = 'DELIVERY_SUBMITTED'
            """, submissionId));
        assertEquals(1, count("""
            SELECT COUNT(*) FROM certification_domain_events
            WHERE subject_id = ? AND event_type = 'delivery.submitted.v1'
            """, submissionId));

        JsonNode certified = F04TestSupport.certifyAccepted(
            mvc, mapper, baseline, submissionId, "user-reliance",
            "vertical-certify");
        assertEquals("ACCEPTED", certified.path("deliverables").get(0)
            .path("certification").path("decision").asText());
        assertEquals("COMPLETED", jdbc.queryForObject("""
            SELECT status FROM certification_rounds WHERE submission_id = ?
            """, String.class, submissionId));
        assertFalse(jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM deliverable_certifications
                WHERE submission_id = ? AND decided_by_subject LIKE 'SYSTEM:%'
            )
            """, Boolean.class, submissionId));

        JsonNode summary = F04TestSupport.summary(
            mvc, mapper, certified.path("version").asLong(), "user-reliance",
            "vertical-summary");
        String checksum = summary.path("summary").path("checksum").asText();
        assertTrue(checksum.matches("[0-9a-f]{64}"));
        assertEquals("CERTIFIED", summary.path("summary").path("decision").asText());
        assertEquals(1, count("""
            SELECT COUNT(*) FROM monthly_certification_summaries
            WHERE engagement_month_id = ?::uuid AND status = 'CURRENT'
            """, MONTH));

        mvc.perform(get("/api/v1/certification/months/{monthId}/readiness", MONTH)
                .with(token("user-reliance")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pillars[0].key").value("ROSTER"))
            .andExpect(jsonPath("$.blockers[*].code")
                .value(org.hamcrest.Matchers.hasItem(
                    "CLOSED_ATTENDANCE_SNAPSHOT_REQUIRED")));

        assertEquals(frozenTitle, jdbc.queryForObject("""
            SELECT title FROM delivery_plan_versions WHERE id = ?
            """, String.class, baseline.planVersionId()));
        assertEquals(baseline.checksum(), jdbc.queryForObject("""
            SELECT checksum FROM delivery_plan_baselines WHERE id = ?
            """, String.class, baseline.baselineId()));
    }

    @Test
    void incompleteDraftStaysEditableAndStaleVersionDoesNotCreateAnotherDraft()
        throws Exception {
        F04TestSupport.FrozenBaseline baseline =
            F04TestSupport.frozenBaseline(mvc, mapper);
        mvc.perform(post("/api/v1/certification/months/{monthId}/submissions", MONTH)
                .with(token("user-arrow"))
                .header("If-Match", "0")
                .header("Idempotency-Key", "incomplete-save")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedMonthVersion":0,
                      "summary":"Incomplete declaration",
                      "declarationAccepted":false,
                      "items":[{
                        "deliverableId":"%s",
                        "outcome":"PARTIALLY_COMPLETED",
                        "completionPercentage":50,
                        "summary":"Partial",
                        "criterionResponses":[{
                          "criterionId":"%s",
                          "response":"Partial response",
                          "evidenceReferenceIds":[]
                        }],
                        "evidenceReferenceIds":[]
                      }]
                    }
                    """.formatted(
                        baseline.deliverableVersionId(), baseline.criterionId())))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.submission.locked").value(false))
            .andExpect(jsonPath("$.submission.completenessBlockers")
                .value(org.hamcrest.Matchers.hasItems(
                    "VENDOR_DECLARATION_REQUIRED",
                    "NON_SIMPLE_OUTCOME_REQUIRES_CAUSE_IMPACT_NEXT_ACTION",
                    "CARRY_FORWARD_PROPOSAL_REQUIRED")));

        UUID submissionId = jdbc.queryForObject("""
            SELECT id FROM delivery_submissions
            WHERE engagement_month_id = ?::uuid AND status = 'DRAFT'
            """, UUID.class, MONTH);
        mvc.perform(post(
                    "/api/v1/certification/submissions/{submissionId}/submit",
                    submissionId)
                .with(token("user-arrow"))
                .header("If-Match", "0")
                .header("Idempotency-Key", "stale-submit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedSubmissionVersion\":0}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("SUBMISSION_VERSION_CONFLICT"))
            .andExpect(jsonPath("$.currentVersion").value(1));
        assertEquals("DRAFT", jdbc.queryForObject("""
            SELECT status FROM delivery_submissions WHERE id = ?
            """, String.class, submissionId));
    }

    @Test
    void identicalDraftRetryReturnsPriorResultWithoutDuplicateFacts()
        throws Exception {
        F04TestSupport.FrozenBaseline baseline =
            F04TestSupport.frozenBaseline(mvc, mapper);
        JsonNode initial = F04TestSupport.workspace(mvc, mapper, "user-arrow");
        F04TestSupport.saveCompleteDraft(
            mvc, mapper, baseline, "user-arrow", initial.path("version").asLong(),
            "same-draft-key");

        // Intended T-DEL-010 behavior: a byte-identical retry returns the prior
        // result despite the month version having advanced.
        F04TestSupport.saveCompleteDraft(
            mvc, mapper, baseline, "user-arrow", initial.path("version").asLong(),
            "same-draft-key");
        assertEquals(1, count("""
            SELECT COUNT(*) FROM delivery_submissions
            WHERE engagement_month_id = ?::uuid
            """, MONTH));
        assertEquals(1, count("""
            SELECT COUNT(*) FROM certification_idempotency_keys
            WHERE operation = 'SAVE_SUBMISSION' AND idempotency_key = ?
            """, "same-draft-key"));
    }

    @Test
    void requiredFrozenEvidenceCannotBeOmitted() throws Exception {
        F04TestSupport.FrozenBaseline baseline =
            F04TestSupport.frozenBaseline(mvc, mapper);
        JsonNode initial = F04TestSupport.workspace(mvc, mapper, "user-arrow");
        JsonNode draft = F04TestSupport.saveCompleteDraft(
            mvc, mapper, baseline, "user-arrow", initial.path("version").asLong(),
            "missing-evidence-save", false);

        mvc.perform(post(
                    "/api/v1/certification/submissions/{submissionId}/submit",
                    draft.path("submission").path("id").asText())
                .with(token("user-arrow"))
                .header("If-Match", "1")
                .header("Idempotency-Key", "missing-evidence-submit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedSubmissionVersion\":1}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("SUBMISSION_INCOMPLETE"))
            .andExpect(jsonPath("$.detail", containsString("EVIDENCE")));
    }

    @Test
    void tenantPartyProjectAndSeparationOfDutiesAreServerEnforced()
        throws Exception {
        F04TestSupport.FrozenBaseline baseline =
            F04TestSupport.frozenBaseline(mvc, mapper, "user-sod",
                F04TestSupport.PROJECT_A);
        JsonNode initial = F04TestSupport.workspace(mvc, mapper, "user-sod");

        mvc.perform(get("/api/v1/certification/months/{monthId}", MONTH)
                .with(token("user-northstar")))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/certification/months/{monthId}", MONTH)
                .with(token("user-disabled")))
            .andExpect(status().isNotFound());

        JsonNode draft = F04TestSupport.saveCompleteDraft(
            mvc, mapper, baseline, "user-sod", initial.path("version").asLong(),
            "sod-save");
        UUID submissionId = UUID.fromString(draft.path("submission").path("id").asText());
        mvc.perform(post(
                    "/api/v1/certification/submissions/{submissionId}/submit",
                    submissionId)
                .with(token("user-reliance"))
                .header("If-Match", "1")
                .header("Idempotency-Key", "client-cannot-submit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedSubmissionVersion\":1}"))
            .andExpect(status().isNotFound());
        F04TestSupport.submit(mvc, mapper, submissionId, 1, "sod-submit");

        // Intended T-CERT-001 / F04-BE-003: a dual vendor/client principal
        // cannot certify the submission it authored when captured policy requires SOD.
        mvc.perform(post(
                    "/api/v1/certification/submissions/{submissionId}/certifications",
                    submissionId)
                .with(token("user-sod"))
                .header("If-Match", "1")
                .header("Idempotency-Key", "sod-self-certification")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedSubmissionVersion":1,
                      "deliverableId":"%s",
                      "decision":"ACCEPTED",
                      "comment":"This must be rejected for SOD",
                      "criterionResults":[{
                        "criterionId":"%s","decision":"MET",
                        "rationale":"Self-reviewed","evidenceViewed":true
                      }]
                    }
                    """.formatted(
                        baseline.deliverableVersionId(), baseline.criterionId())))
            .andExpect(status().isNotFound());
        assertEquals(0, count("""
            SELECT COUNT(*) FROM deliverable_certifications
            WHERE submission_id = ?
            """, submissionId));
    }

    @Test
    void dualVendorClientCannotCertifyAnotherVendorUsersSubmission()
        throws Exception {
        F04TestSupport.FrozenBaseline baseline =
            F04TestSupport.frozenBaseline(
                mvc, mapper, "user-sod", F04TestSupport.PROJECT_A);
        JsonNode initial =
            F04TestSupport.workspace(mvc, mapper, "user-arrow");
        JsonNode draft = F04TestSupport.saveCompleteDraft(
            mvc, mapper, baseline, "user-arrow",
            initial.path("version").asLong(), "sod-other-vendor-save");
        UUID submissionId = UUID.fromString(
            draft.path("submission").path("id").asText());
        F04TestSupport.submit(
            mvc, mapper, submissionId, 1, "sod-other-vendor-submit");

        mvc.perform(post(
                    "/api/v1/certification/submissions/{submissionId}/certifications",
                    submissionId)
                .with(token("user-sod"))
                .header("If-Match", "1")
                .header("Idempotency-Key", "sod-other-vendor-certification")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedSubmissionVersion":1,
                      "deliverableId":"%s",
                      "decision":"ACCEPTED",
                      "comment":"Dual-party authority must not certify.",
                      "criterionResults":[{
                        "criterionId":"%s","decision":"MET",
                        "rationale":"Must be denied","evidenceViewed":true
                      }]
                    }
                    """.formatted(
                        baseline.deliverableVersionId(),
                        baseline.criterionId())))
            .andExpect(status().isNotFound());

        assertEquals(0, count("""
            SELECT COUNT(*) FROM deliverable_certifications
            WHERE submission_id = ?
            """, submissionId));
    }

    @Test
    void clarificationIsAdditiveAndTerminalSummaryChecksumIsDeterministic()
        throws Exception {
        F04TestSupport.FrozenBaseline baseline =
            F04TestSupport.frozenBaseline(mvc, mapper);
        JsonNode initial = F04TestSupport.workspace(mvc, mapper, "user-arrow");
        JsonNode draft = F04TestSupport.saveCompleteDraft(
            mvc, mapper, baseline, "user-arrow", initial.path("version").asLong(),
            "clarify-save");
        UUID submissionId = UUID.fromString(draft.path("submission").path("id").asText());
        F04TestSupport.submit(mvc, mapper, submissionId, 1, "clarify-submit");
        String originalSummary = jdbc.queryForObject("""
            SELECT summary FROM delivery_submissions WHERE id = ?
            """, String.class, submissionId);

        JsonNode questioned = mapper.readTree(mvc.perform(post(
                    "/api/v1/certification/submissions/{submissionId}/clarifications",
                    submissionId)
                .with(token("user-reliance"))
                .header("If-Match", "1")
                .header("Idempotency-Key", "clarify-question")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedSubmissionVersion":1,
                      "deliverableId":"%s",
                      "questions":["Provide the deterministic execution reference"]
                    }
                    """.formatted(baseline.deliverableVersionId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.clarifications[0].status").value("OPEN"))
            .andReturn().getResponse().getContentAsString());
        String clarificationId = questioned.path("clarifications").get(0)
            .path("id").asText();

        mvc.perform(post(
                    "/api/v1/certification/submissions/{submissionId}/clarifications",
                    submissionId)
                .with(token("user-arrow"))
                .header("If-Match", "1")
                .header("Idempotency-Key", "clarify-response")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedSubmissionVersion":1,
                      "deliverableId":"%s",
                      "clarificationId":"%s",
                      "response":"Recorded as an additive vendor response"
                    }
                    """.formatted(baseline.deliverableVersionId(), clarificationId)))
            .andExpect(status().isOk());
        assertEquals(originalSummary, jdbc.queryForObject("""
            SELECT summary FROM delivery_submissions WHERE id = ?
            """, String.class, submissionId));
        assertEquals(1, count("""
            SELECT COUNT(*) FROM delivery_submission_responses
            WHERE submission_id = ?
            """, submissionId));
        assertEquals(2, count("""
            SELECT COUNT(*) FROM certification_rounds WHERE submission_id = ?
            """, submissionId));

        JsonNode certified = F04TestSupport.certifyAccepted(
            mvc, mapper, baseline, submissionId, "user-reliance",
            "clarify-certify");
        JsonNode first = F04TestSupport.summary(
            mvc, mapper, certified.path("version").asLong(), "user-reliance",
            "clarify-summary-1");
        String firstChecksum = first.path("summary").path("checksum").asText();
        JsonNode retried = F04TestSupport.summary(
            mvc, mapper, first.path("version").asLong(), "user-reliance",
            "clarify-summary-2");
        assertEquals(firstChecksum, retried.path("summary").path("checksum").asText());
        assertEquals(1, count("""
            SELECT COUNT(*) FROM monthly_certification_summaries
            WHERE engagement_month_id = ?::uuid
            """, MONTH));
    }

    @Test
    void criterionAndDecisionValidationNeverTreatLinearOrPercentageAsAcceptance()
        throws Exception {
        F04TestSupport.FrozenBaseline baseline =
            F04TestSupport.frozenBaseline(mvc, mapper);
        JsonNode initial = F04TestSupport.workspace(mvc, mapper, "user-arrow");
        JsonNode draft = F04TestSupport.saveCompleteDraft(
            mvc, mapper, baseline, "user-arrow", initial.path("version").asLong(),
            "decision-save");
        UUID submissionId = UUID.fromString(draft.path("submission").path("id").asText());
        F04TestSupport.submit(mvc, mapper, submissionId, 1, "decision-submit");

        assertEquals(0, count("""
            SELECT COUNT(*) FROM deliverable_certifications
            WHERE submission_id = ?
            """, submissionId));
        assertEquals("COMPLETED", jdbc.queryForObject("""
            SELECT declared_outcome FROM deliverable_delivery_outcomes
            WHERE submission_id = ?
            """, String.class, submissionId));

        mvc.perform(post(
                    "/api/v1/certification/submissions/{submissionId}/certifications",
                    submissionId)
                .with(token("user-reliance"))
                .header("If-Match", "1")
                .header("Idempotency-Key", "bad-criterion")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedSubmissionVersion":1,
                      "deliverableId":"%s",
                      "decision":"REJECTED",
                      "comment":"Rejected",
                      "cause":"Mismatch",
                      "nextAction":"Correct it",
                      "criterionResults":[{
                        "criterionId":"ffffffff-ffff-ffff-ffff-ffffffffffff",
                        "decision":"NOT_MET",
                        "rationale":"Wrong criterion","evidenceViewed":false
                      }]
                    }
                    """.formatted(baseline.deliverableVersionId())))
            .andExpect(status().isNotFound());
        assertEquals(0, count("""
            SELECT COUNT(*) FROM monthly_certification_summaries
            WHERE engagement_month_id = ?::uuid
            """, MONTH));
    }

    @Test
    void readinessIsIdempotentForSameManifestAndVersionsChangedInputs()
        throws Exception {
        F04TestSupport.completedCertification(mvc, mapper, jdbc);
        JsonNode first = mapper.readTree(mvc.perform(get(
                    "/api/v1/certification/months/{monthId}/readiness", MONTH)
                .with(token("user-reliance")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACTION_REQUIRED"))
            .andReturn().getResponse().getContentAsString());
        JsonNode second = mapper.readTree(mvc.perform(get(
                    "/api/v1/certification/months/{monthId}/readiness", MONTH)
                .with(token("user-reliance")))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        assertEquals(first.path("inputManifestVersion").asText(),
            second.path("inputManifestVersion").asText());
        assertEquals(1, count("""
            SELECT COUNT(*) FROM certification_readiness_runs
            WHERE engagement_month_id = ?::uuid
            """, MONTH));

        // A material recipient/readiness input change must create a new manifest.
        // Direct mutation is blocked by F03 immutability, so a later version is
        // represented by a pending plan revision.
        String firstManifest = first.path("inputManifestVersion").asText();
        jdbc.update("""
            INSERT INTO delivery_plan_versions
                (id, plan_id, version, state, title, summary, business_outcomes,
                 coordinator_subject, baseline_type, quorum_mode,
                 quorum_required, prior_version_id, revision_reason,
                 revision_impact, created_by_subject)
            SELECT gen_random_uuid(), plan_id, 2, 'DRAFT', title, summary,
                   business_outcomes, coordinator_subject, baseline_type,
                   quorum_mode, quorum_required, id, 'Correction',
                   'Readiness invalidation', 'user-arrow'
            FROM delivery_plan_versions WHERE id = ?::uuid
            """, F04TestSupport.workspace(mvc, mapper, "user-arrow")
                .path("baseline").path("versionId").asText());
        JsonNode changed = mapper.readTree(mvc.perform(get(
                    "/api/v1/certification/months/{monthId}/readiness", MONTH)
                .with(token("user-reliance")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.blockers[*].code")
                .value(org.hamcrest.Matchers.hasItem("PLAN_REVISION_PENDING")))
            .andReturn().getResponse().getContentAsString());
        assertNotEquals(firstManifest, changed.path("inputManifestVersion").asText());
        assertEquals(2, count("""
            SELECT COUNT(*) FROM certification_readiness_runs
            WHERE engagement_month_id = ?::uuid
            """, MONTH));
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}
