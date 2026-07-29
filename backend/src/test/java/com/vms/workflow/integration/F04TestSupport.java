package com.vms.workflow.integration;

import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

final class F04TestSupport {
    static final String MONTH = "00000000-0000-0000-0000-000000000602";
    static final String ENGAGEMENT = "00000000-0000-0000-0000-000000000401";
    static final String PROJECT_A = "00000000-0000-0000-0000-000000000501";
    static final String PROJECT_B = "00000000-0000-0000-0000-000000000502";
    static final String EMPLOYEE = "00000000-0000-0000-0000-000000000801";
    static final String EVIDENCE =
        "00000000-0000-0000-0000-000000000904";

    private F04TestSupport() {
    }

    static RequestPostProcessor token(String subject) {
        return jwt().jwt(value -> value.subject(subject).audience(List.of("vms-api")));
    }

    static FrozenBaseline frozenBaseline(
        MockMvc mvc,
        ObjectMapper mapper
    ) throws Exception {
        return frozenBaseline(mvc, mapper, "user-reliance", PROJECT_A);
    }

    static FrozenBaseline frozenBaseline(
        MockMvc mvc,
        ObjectMapper mapper,
        String productOwner,
        String projectId
    ) throws Exception {
        String createdBody = mvc.perform(post("/api/v1/delivery/plans")
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "engagementMonthId":"%s",
                      "title":"F04 deterministic frozen plan",
                      "summary":"Frozen source facts for certification automation",
                      "businessOutcomes":"Certification never mutates this baseline",
                      "coordinatorSubject":"user-arrow",
                      "baselineType":"ON_TIME",
                      "quorumMode":"ANY_ONE",
                      "quorumRequired":1,
                      "approverSubjects":["user-approver"],
                      "recipients":{
                        "arrowFoundry":["shared@example.test"],
                        "relianceStakeholders":["owner@example.test"],
                        "procurementCc":["shared@example.test"]
                      },
                      "deliverables":[{
                        "deliverableCode":"F04-001",
                        "title":"Certification source deliverable",
                        "description":"A frozen deterministic F03 deliverable",
                        "businessObjective":"Prove exact-scope F04 behavior",
                        "projectId":"%s",
                        "productOwnerSubject":"%s",
                        "vendorOwnerSubject":"user-arrow",
                        "priority":"P1",
                        "targetCompletionDate":"2026-07-31",
                        "evidenceExpectations":"A mandatory immutable test report",
                        "dependencyNoneDeclared":true,
                        "riskAndAssumptions":"Provider-neutral local automation",
                        "deliveryCategory":"QUALITY",
                        "linkExceptionReason":"Recorded provider-unavailable exception",
                        "criteria":[{
                          "statement":"The exact frozen criterion is satisfied",
                          "validationMethod":"JUnit and PostgreSQL",
                          "expectedResult":"Immutable evidence and explicit decision",
                          "mandatory":true
                        }],
                        "dependencies":[],
                        "assignments":[{
                          "employeeId":"%s",
                          "effectiveFrom":"2026-07-01",
                          "effectiveTo":"2026-07-31"
                        }]
                      }]
                    }
                    """.formatted(MONTH, projectId, productOwner, EMPLOYEE)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        JsonNode created = mapper.readTree(createdBody);
        String planId = created.path("id").asText();
        String deliverableVersionId = created.path("deliverables").get(0)
            .path("deliverableVersionId").asText();
        String criterionId = created.path("deliverables").get(0)
            .path("criteria").get(0).path("id").asText();

        mvc.perform(post("/api/v1/delivery/plans/{planId}/submit", planId)
                .with(token("user-arrow")))
            .andExpect(status().isOk());
        String frozenBody = mvc.perform(post(
                    "/api/v1/delivery/plans/{planId}/approvals", planId)
                .with(token("user-approver"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"decision":"APPROVE","comment":"Frozen for F04 automation"}
                    """))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        JsonNode frozen = mapper.readTree(frozenBody);
        return new FrozenBaseline(
            UUID.fromString(planId),
            UUID.fromString(frozen.path("currentVersionId").asText()),
            UUID.fromString(frozen.path("baselineId").asText()),
            UUID.fromString(deliverableVersionId),
            UUID.fromString(criterionId),
            frozen.path("checksum").asText());
    }

    static JsonNode workspace(MockMvc mvc, ObjectMapper mapper, String subject)
        throws Exception {
        return json(mapper, mvc.perform(get("/api/v1/certification/months/{monthId}", MONTH)
                .with(token(subject)))
            .andExpect(status().isOk())
            .andReturn());
    }

    static JsonNode saveCompleteDraft(
        MockMvc mvc,
        ObjectMapper mapper,
        FrozenBaseline baseline,
        String subject,
        long monthVersion,
        String key
    ) throws Exception {
        return saveCompleteDraft(
            mvc, mapper, baseline, subject, monthVersion, key, true);
    }

    static JsonNode saveCompleteDraft(
        MockMvc mvc,
        ObjectMapper mapper,
        FrozenBaseline baseline,
        String subject,
        long monthVersion,
        String key,
        boolean includeRequiredEvidence
    ) throws Exception {
        String criterionEvidence = includeRequiredEvidence
            ? "\"" + EVIDENCE + "\"" : "";
        return json(mapper, mvc.perform(post(
                    "/api/v1/certification/months/{monthId}/submissions", MONTH)
                .with(token(subject))
                .header("If-Match", Long.toString(monthVersion))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedMonthVersion":%d,
                      "summary":"Complete vendor delivery declaration",
                      "declarationAccepted":true,
                      "items":[{
                        "deliverableId":"%s",
                        "outcome":"COMPLETED",
                        "completionPercentage":100,
                        "completionDate":"2026-07-31",
                        "summary":"Delivered against the frozen criterion",
                        "criterionResponses":[{
                          "criterionId":"%s",
                          "response":"Met through the recorded deterministic test",
                          "evidenceReferenceIds":[%s]
                        }],
                        "evidenceReferenceIds":[]
                      }]
                    }
                    """.formatted(
                        monthVersion, baseline.deliverableVersionId(),
                        baseline.criterionId(), criterionEvidence)))
            .andExpect(status().isCreated())
            .andReturn());
    }

    static JsonNode submit(
        MockMvc mvc,
        ObjectMapper mapper,
        UUID submissionId,
        int submissionVersion,
        String key
    ) throws Exception {
        return json(mapper, mvc.perform(post(
                    "/api/v1/certification/submissions/{submissionId}/submit",
                    submissionId)
                .with(token("user-arrow"))
                .header("If-Match", Integer.toString(submissionVersion))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"expectedSubmissionVersion":%d}
                    """.formatted(submissionVersion)))
            .andExpect(status().isOk())
            .andReturn());
    }

    static JsonNode certifyAccepted(
        MockMvc mvc,
        ObjectMapper mapper,
        FrozenBaseline baseline,
        UUID submissionId,
        String subject,
        String key
    ) throws Exception {
        return json(mapper, mvc.perform(post(
                    "/api/v1/certification/submissions/{submissionId}/certifications",
                    submissionId)
                .with(token(subject))
                .header("If-Match", "1")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedSubmissionVersion":1,
                      "deliverableId":"%s",
                      "decision":"ACCEPTED",
                      "comment":"Explicit product-owner acceptance",
                      "criterionResults":[{
                        "criterionId":"%s",
                        "decision":"MET",
                        "rationale":"Reviewed against the frozen criterion",
                        "evidenceViewed":true
                      }]
                    }
                    """.formatted(
                        baseline.deliverableVersionId(), baseline.criterionId())))
            .andExpect(status().isOk())
            .andReturn());
    }

    static JsonNode summary(
        MockMvc mvc,
        ObjectMapper mapper,
        long monthVersion,
        String subject,
        String key
    ) throws Exception {
        return json(mapper, mvc.perform(post(
                    "/api/v1/certification/months/{monthId}/summaries", MONTH)
                .with(token(subject))
                .header("If-Match", Long.toString(monthVersion))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedMonthVersion":%d,
                      "decision":"CERTIFIED",
                      "observations":"Explicit monthly decision"
                    }
                    """.formatted(monthVersion)))
            .andExpect(status().isCreated())
            .andReturn());
    }

    static CompletedCertification completedCertification(
        MockMvc mvc,
        ObjectMapper mapper,
        JdbcTemplate jdbc
    ) throws Exception {
        FrozenBaseline baseline = frozenBaseline(mvc, mapper);
        JsonNode initial = workspace(mvc, mapper, "user-arrow");
        JsonNode draft = saveCompleteDraft(
            mvc, mapper, baseline, "user-arrow", initial.path("version").asLong(),
            "f04-save-complete");
        UUID submissionId = UUID.fromString(draft.path("submission").path("id").asText());
        JsonNode submitted = submit(
            mvc, mapper, submissionId, 1, "f04-submit-complete");
        JsonNode certified = certifyAccepted(
            mvc, mapper, baseline, submissionId, "user-reliance",
            "f04-certify-complete");
        JsonNode summarized = summary(
            mvc, mapper, certified.path("version").asLong(), "user-reliance",
            "f04-summary-complete");
        UUID attendanceId = UUID.fromString(
            "44000000-0000-0000-0000-" + UUID.randomUUID().toString().substring(24));
        jdbc.update("""
            INSERT INTO attendance_snapshot_versions
                (id, engagement_month_id, version, status, checksum, day_count,
                 closed_at, closed_by_subject)
            VALUES (?, ?::uuid, 1, 'CLOSED', repeat('a', 64), 0,
                    '2026-07-31T23:59:00Z', 'test-fixture')
            """, attendanceId, MONTH);
        return new CompletedCertification(
            baseline, submissionId,
            UUID.fromString(summarized.path("summary").path("id").asText()),
            attendanceId, summarized.path("version").asLong());
    }

    static JsonNode createConfirmationRequest(
        MockMvc mvc,
        ObjectMapper mapper,
        long monthVersion,
        OffsetDateTime dueAt,
        String key
    ) throws Exception {
        return json(mapper, mvc.perform(post(
                    "/api/v1/certification/months/{monthId}/confirmation-requests",
                    MONTH)
                .with(token("user-governance"))
                .header("If-Match", Long.toString(monthVersion))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"expectedMonthVersion":%d,"dueAt":"%s"}
                    """.formatted(monthVersion, dueAt)))
            .andExpect(status().isCreated())
            .andReturn());
    }

    static DirectConfirmation directConfirmation(
        JdbcTemplate jdbc,
        CompletedCertification completed,
        String quorumMode,
        int quorumRequired,
        OffsetDateTime requestedAt,
        OffsetDateTime dueAt,
        List<EligibleFixture> eligible
    ) {
        UUID requestId = UUID.randomUUID();
        UUID policyId = jdbc.queryForObject("""
            SELECT policy_version_id FROM delivery_submissions WHERE id = ?
            """, UUID.class, completed.submissionId());
        int requestVersion = jdbc.queryForObject("""
            SELECT COALESCE(MAX(version), 0) + 1
            FROM business_confirmation_requests
            WHERE engagement_month_id = ?::uuid
            """, Integer.class, MONTH);
        jdbc.update("""
            INSERT INTO business_confirmation_requests
                (id, engagement_month_id, attendance_snapshot_id,
                 plan_version_id, baseline_id, certification_summary_id,
                 policy_version_id, version, status, transport_status,
                 quorum_mode, quorum_required, recipient_snapshot,
                 eligibility_snapshot, scope_manifest, scope_checksum,
                 requested_at, due_at, created_by_subject)
            VALUES (?, ?::uuid, ?, ?, ?, ?, ?, ?, 'AWAITING_RESPONSE',
                    'NOT_CONFIGURED', ?, ?,
                    '{"to":[{"display":"owner@example.test","roleReason":"CLIENT_STAKEHOLDER"}],"cc":[{"display":"procurement@example.test","roleReason":"CENTRAL_PROCUREMENT"}]}'::jsonb,
                    '{"captured":true}'::jsonb,
                    ?::jsonb, repeat('b', 64), ?, ?, 'user-governance')
            """, requestId, MONTH, completed.attendanceSnapshotId(),
            completed.baseline().planVersionId(), completed.baseline().baselineId(),
            completed.summaryId(), policyId, requestVersion,
            quorumMode, quorumRequired,
            """
                {"schema":"business-confirmation-scope-v1",
                 "attendanceSnapshotId":"%s","planVersionId":"%s",
                 "baselineId":"%s","certificationSummaryId":"%s",
                 "dueAt":"%s"}
                """.formatted(
                    completed.attendanceSnapshotId(),
                    completed.baseline().planVersionId(),
                    completed.baseline().baselineId(),
                    completed.summaryId(), dueAt),
            requestedAt, dueAt);
        int sequence = 1;
        for (EligibleFixture value : eligible) {
            UUID snapshotId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO confirmation_eligibility_snapshots
                    (id, engagement_month_id, policy_version_id,
                     eligible_confirmer_subject, verified_email, project_id,
                     sequence_number, authority_snapshot)
                VALUES (?, ?::uuid, ?, ?, ?, ?::uuid, ?,
                        '{"roleReason":"ASSIGNED_PRODUCT_OWNER","resolvedServerSide":true}'::jsonb)
                """, snapshotId, MONTH, policyId, value.subject(), value.email(),
                value.projectId(), sequence);
            jdbc.update("""
                INSERT INTO confirmation_request_eligibility
                    (request_id, eligibility_id, eligible_confirmer_subject,
                     project_id, sequence_number)
                VALUES (?, ?, ?, ?::uuid, ?)
                """, requestId, snapshotId, value.subject(), value.projectId(),
                sequence++);
        }
        // Mirror BusinessConfirmationService#create: a directly seeded
        // awaiting-response request must also move the governed month into the
        // confirmation-pending state. V34 deliberately rejects the otherwise
        // impossible DELIVERY_REVIEW -> CONFIRMED shortcut.
        jdbc.update("""
            UPDATE engagement_months
            SET certification_version = certification_version + 1,
                state = 'CONFIRMATION_PENDING',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?::uuid
            """, MONTH);
        return new DirectConfirmation(
            requestId, policyId, requestVersion, dueAt);
    }

    private static JsonNode json(ObjectMapper mapper, MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    record FrozenBaseline(
        UUID planId,
        UUID planVersionId,
        UUID baselineId,
        UUID deliverableVersionId,
        UUID criterionId,
        String checksum
    ) {
    }

    record CompletedCertification(
        FrozenBaseline baseline,
        UUID submissionId,
        UUID summaryId,
        UUID attendanceSnapshotId,
        long monthVersion
    ) {
    }

    record EligibleFixture(String subject, String email, String projectId) {
    }

    record DirectConfirmation(
        UUID requestId,
        UUID policyId,
        int version,
        OffsetDateTime dueAt
    ) {
    }
}
