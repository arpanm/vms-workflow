package com.vms.workflow.integration;

import org.junit.jupiter.api.BeforeEach;
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

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:tc:vmspostgresql:18-alpine:///vms_workflow_client_collaboration_it",
    "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.flyway.locations=classpath:db/migration,classpath:db/testdata",
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:9/test-jwks",
    "vms.security.issuer=https://issuer.example.test",
    "vms.security.audience=vms-api"
})
@AutoConfigureMockMvc
@Transactional
class ClientCollaborationIT {
    private static final String ENGAGEMENT =
        "00000000-0000-0000-0000-000000000401";
    private static final String PROJECT =
        "00000000-0000-0000-0000-000000000501";
    private static final String CLIENT_ORG =
        "00000000-0000-0000-0000-000000000102";
    private static final String VENDOR_ORG =
        "00000000-0000-0000-0000-000000000101";
    private static final String CLIENT_USER =
        "00000000-0000-0000-0000-000000000202";
    private static final String EMPLOYEE_USER =
        "00000000-0000-0000-0000-000000000212";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    private UUID augustMonth;

    @BeforeEach
    void setUp() {
        augustMonth = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO engagement_months(
                id, engagement_id, month_start_date, state, risk_status)
            VALUES (?, ?::uuid, '2026-08-01', 'PLANNING', 'ON_TRACK')
            ON CONFLICT (engagement_id, month_start_date)
            DO UPDATE SET updated_at = engagement_months.updated_at
            """, augustMonth, ENGAGEMENT);
        augustMonth = jdbc.queryForObject("""
            SELECT id FROM engagement_months
            WHERE engagement_id = ?::uuid AND month_start_date = '2026-08-01'
            """, UUID.class, ENGAGEMENT);
    }

    @Test
    void platformAdminOnboardsClientAndAddsPermissionBoundUsers()
        throws Exception {
        JsonNode client = json(mvc.perform(post("/api/v1/collaboration/clients")
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "clientCode":"ACME_TEST",
                      "legalName":"Acme Test Private Limited",
                      "displayName":"Acme Test",
                      "primaryDomain":"acme.example",
                      "timezone":"Asia/Kolkata",
                      "engagementCode":"ACME_AF_TEST",
                      "engagementName":"Acme / ArrowFoundry",
                      "vendorOrganizationId":"%s",
                      "procurementOrganizationId":null,
                      "engagementModel":"DEDICATED_RESOURCE_MONTHLY",
                      "startDate":"2026-08-01",
                      "projectCode":"ACME_CORE",
                      "projectName":"Acme Core"
                    }
                    """.formatted(VENDOR_ORG)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.provisionedMonthCount").value(13))
            .andReturn().getResponse().getContentAsString());

        String clientId = client.path("organizationId").asText();
        mvc.perform(post(
                "/api/v1/collaboration/clients/{clientId}/users", clientId)
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "identitySubject":"acme-product-owner",
                      "email":"owner@acme.example",
                      "displayName":"Acme Product Owner",
                      "roleCodes":["CLIENT_PRODUCT_OWNER","CLIENT_APPROVER"],
                      "validFrom":"2026-07-01",
                      "validTo":null
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.roleCodes.length()").value(2))
            .andExpect(jsonPath(
                "$.permissions[?(@ == 'workitem.plan.approve')]").exists())
            .andExpect(jsonPath(
                "$.permissions[?(@ == 'workitem.delivery.approve.l2')]").exists());

        mvc.perform(get(
                "/api/v1/collaboration/clients/{clientId}/users", clientId)
                .with(token("user-arrow")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].email").value("owner@acme.example"));

        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM work_item_audit_events
            WHERE engagement_id = ?::uuid
              AND event_type = 'CLIENT_ONBOARDED'
              AND actor_subject = 'user-arrow'
            """, Integer.class, client.path("engagementId").asText()));
    }

    @Test
    void taskCollaborationCoversMentionsAssignmentsEstimatesEffortAndApprovals()
        throws Exception {
        JsonNode created = createTask("COLLAB_TEST");
        String taskId = created.path("id").asText();

        mvc.perform(post(
                "/api/v1/collaboration/work-items/{id}/comments", taskId)
                .with(token("user-employee"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "body":"@client please review the acceptance criteria",
                      "mentionedUserIds":["%s"]
                    }
                    """.formatted(CLIENT_USER)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.comments[0].mentionedUserIds[0]")
                .value(CLIENT_USER));

        JsonNode estimated = json(mvc.perform(post(
                "/api/v1/collaboration/work-items/{id}/estimates", taskId)
                .with(token("user-employee"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"userProfileId":"%s","hours":8.5,"note":"Implementation"}
                    """.formatted(EMPLOYEE_USER)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.totalEstimateHours").value(8.5))
            .andReturn().getResponse().getContentAsString());
        String estimateId = estimated.path("estimates").get(0).path("id").asText();

        mvc.perform(post(
                "/api/v1/collaboration/work-items/{id}/efforts", taskId)
                .with(token("user-employee"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "userProfileId":"%s",
                      "workDate":"2026-08-05",
                      "hours":3.25,
                      "note":"Initial implementation"
                    }
                    """.formatted(EMPLOYEE_USER)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.totalEffortHours").value(3.25));

        mvc.perform(delete(
                "/api/v1/collaboration/work-items/{id}/estimates/{estimateId}",
                taskId, estimateId).with(token("user-employee")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalEstimateHours").value(0));

        mvc.perform(post(
                "/api/v1/collaboration/work-items/{id}/approvals", taskId)
                .with(token("user-reliance"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedVersion":0,
                      "stage":"PLAN_L1",
                      "decision":"APPROVED",
                      "stackRank":2,
                      "comment":"Approved for next month"
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.stackRank").value(2))
            .andExpect(jsonPath("$.lifecycleStatus").value("APPROVED"));

        mvc.perform(get("/api/v1/collaboration/work-items")
                .param("engagementId", ENGAGEMENT)
                .param("mentionedToMe", "true")
                .with(token("user-reliance")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(taskId));
    }

    @Test
    void assigneeCanUpdateAndTransferButUnrelatedUserCannotAssignThirdParty()
        throws Exception {
        JsonNode created = createTask("ASSIGNMENT_TEST");
        String taskId = created.path("id").asText();

        mvc.perform(patch(
                "/api/v1/collaboration/work-items/{id}/delivery-status", taskId)
                .with(token("user-employee"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedVersion":0,
                      "lifecycleStatus":"IN_PROGRESS",
                      "deliverySummary":"Implementation started"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value(1));

        mvc.perform(post(
                "/api/v1/collaboration/work-items/{id}/assignments", taskId)
                .with(token("user-employee"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "userProfileId":"%s",
                      "discipline":"PRODUCT_MANAGER"
                    }
                    """.formatted(CLIENT_USER)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.assignments.length()").value(2));

        mvc.perform(post(
                "/api/v1/collaboration/work-items/{id}/assignments", taskId)
                .with(token("user-reliance-delegate"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "userProfileId":"%s",
                      "discipline":"QA"
                    }
                    """.formatted(EMPLOYEE_USER)))
            .andExpect(status().isNotFound());
    }

    @Test
    void l2AuthorityIsDistinctAndTaskScopeDoesNotLeak()
        throws Exception {
        JsonNode created = createTask("L2_TEST");
        String taskId = created.path("id").asText();
        jdbc.update("""
            INSERT INTO role_assignments(
                id, user_profile_id, organization_id, role_id,
                scope_type, scope_id, status, valid_from)
            VALUES (?, '00000000-0000-0000-0000-000000000221',
                    ?::uuid, '11000000-0000-0000-0000-000000000013',
                    'ENGAGEMENT', ?::uuid, 'ACTIVE', '2020-01-01')
            """, UUID.randomUUID(), CLIENT_ORG, ENGAGEMENT);

        mvc.perform(post(
                "/api/v1/collaboration/work-items/{id}/approvals", taskId)
                .with(token("user-approver-2"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedVersion":0,
                      "stage":"DELIVERY_L2",
                      "decision":"APPROVED",
                      "stackRank":null,
                      "comment":"L2 accepted"
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.approvals[0].stage").value("DELIVERY_L2"));

        mvc.perform(get(
                "/api/v1/collaboration/work-items/{id}", taskId)
                .with(token("user-northstar")))
            .andExpect(status().isNotFound());

        assertTrue(jdbc.queryForObject("""
            SELECT EXISTS (
              SELECT 1 FROM work_item_audit_events
              WHERE work_item_id = ?::uuid
                AND event_type = 'WORK_ITEM_DELIVERY_L2')
            """, Boolean.class, taskId));
    }

    @Test
    void bulkCreateAcceptsOneEngagementAndRejectsMixedScopeAtomically()
        throws Exception {
        String item = """
            {
              "engagementId":"%s",
              "projectId":"%s",
              "engagementMonthId":"%s",
              "workItemCode":"%s",
              "title":"Bulk governed task",
              "description":"Bulk-created on behalf of the client",
              "workflowDescription":"Plan, execute and verify",
              "acceptanceCriteria":"The complete batch is accepted",
              "priority":"P2",
              "lifecycleStatus":"PLANNED",
              "createdOnBehalfOfClient":true,
              "links":[],
              "assignments":[]
            }
            """;
        mvc.perform(post("/api/v1/collaboration/work-items/bulk")
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("[%s,%s]".formatted(
                    item.formatted(ENGAGEMENT, PROJECT, augustMonth, "BULK_ONE"),
                    item.formatted(ENGAGEMENT, PROJECT, augustMonth, "BULK_TWO"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].createdOnBehalfOfClient").value(true));

        mvc.perform(post("/api/v1/collaboration/work-items/bulk")
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("[%s,%s]".formatted(
                    item.formatted(ENGAGEMENT, PROJECT, augustMonth, "ATOMIC_ONE"),
                    item.formatted(
                        "00000000-0000-0000-0000-000000000402",
                        PROJECT, augustMonth, "ATOMIC_TWO"))))
            .andExpect(status().isBadRequest());

        assertEquals(0, jdbc.queryForObject("""
            SELECT COUNT(*) FROM work_items
            WHERE work_item_code IN ('ATOMIC_ONE', 'ATOMIC_TWO')
            """, Integer.class));
    }

    private JsonNode createTask(String code) throws Exception {
        return json(mvc.perform(post("/api/v1/collaboration/work-items")
                .with(token("user-reliance"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "engagementId":"%s",
                      "projectId":"%s",
                      "engagementMonthId":"%s",
                      "workItemCode":"%s",
                      "title":"Collaborative delivery task",
                      "description":"Implement the complete requested flow",
                      "workflowDescription":"Plan, execute, test and approve",
                      "acceptanceCriteria":"All role-specific acceptance passes",
                      "priority":"P1",
                      "lifecycleStatus":"PLANNED",
                      "createdOnBehalfOfClient":false,
                      "links":[{
                        "linkType":"PRD",
                        "label":"Product requirement",
                        "url":"https://docs.example.test/prd"
                      }],
                      "assignments":[{
                        "userProfileId":"%s",
                        "discipline":"DEVELOPER"
                      }]
                    }
                    """.formatted(
                        ENGAGEMENT, PROJECT, augustMonth, code, EMPLOYEE_USER)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.assignments[0].discipline")
                .value("DEVELOPER"))
            .andExpect(jsonPath("$.links[0].linkType").value("PRD"))
            .andReturn().getResponse().getContentAsString());
    }

    private JsonNode json(String value) {
        return mapper.readTree(value);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor
    token(String subject) {
        return jwt().jwt(value ->
            value.subject(subject).audience(List.of("vms-api")));
    }
}
