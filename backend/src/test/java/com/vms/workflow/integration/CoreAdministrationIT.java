package com.vms.workflow.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:tc:vmspostgresql:18-alpine:///vms_workflow_core_administration_it",
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
class CoreAdministrationIT {
    private static final String ENGAGEMENT =
        "00000000-0000-0000-0000-000000000401";
    private static final String NORTHSTAR_ENGAGEMENT =
        "00000000-0000-0000-0000-000000000402";
    private static final String RELIANCE_ORG =
        "00000000-0000-0000-0000-000000000102";
    private static final String NORTHSTAR_ORG =
        "00000000-0000-0000-0000-000000000104";
    private static final String NAM_PROJECT =
        "00000000-0000-0000-0000-000000000501";
    private static final String SHOPOS_PROJECT =
        "00000000-0000-0000-0000-000000000502";
    private static final String RELIANCE_USER =
        "00000000-0000-0000-0000-000000000202";
    private static final String ADMIN_USER =
        "90000000-0000-0000-0000-000000000201";
    private static final String DELEGATE_USER =
        "90000000-0000-0000-0000-000000000202";
    private static final String NAM_PRODUCT_OWNER =
        "90000000-0000-0000-0000-000000000203";
    private static final String REOPEN_AUTHORITY =
        "90000000-0000-0000-0000-000000000204";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void sessionPublishesEffectivePermissionsAndOrganizationCompatibility()
        throws Exception {
        mvc.perform(get("/api/v1/me").with(token("user-engagement-admin")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.organizationIds[0]").value(RELIANCE_ORG))
            .andExpect(jsonPath("$.permissions").isArray())
            .andExpect(jsonPath("$.permissions[?(@ == 'contacts.manage')]").exists())
            .andExpect(jsonPath("$.permissions[?(@ == 'month.transition')]").exists());
    }

    @Test
    void engagementMutationIsScopedAndOptimisticallyVersioned()
        throws Exception {
        mvc.perform(patch("/api/v1/core/engagements/{id}", ENGAGEMENT)
                .with(token("user-engagement-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "name":"Reliance governed delivery",
                      "status":"ACTIVE",
                      "defaultProjectId":"%s",
                      "expectedVersion":0
                    }
                    """.formatted(NAM_PROJECT)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Reliance governed delivery"))
            .andExpect(jsonPath("$.version").value(1));

        mvc.perform(patch("/api/v1/core/engagements/{id}", ENGAGEMENT)
                .with(token("user-engagement-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "name":"Stale overwrite",
                      "status":"ACTIVE",
                      "defaultProjectId":"%s",
                      "expectedVersion":0
                    }
                    """.formatted(NAM_PROJECT)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ENGAGEMENT_VERSION_CONFLICT"))
            .andExpect(jsonPath("$.currentVersion").value(1));

        mvc.perform(patch("/api/v1/core/engagements/{id}", NORTHSTAR_ENGAGEMENT)
                .with(token("user-engagement-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"forged","status":"ACTIVE","expectedVersion":0}
                    """))
            .andExpect(status().isNotFound());
    }

    @Test
    void configurationResolvesByRepresentedDateAndRejectsOverlap()
        throws Exception {
        mvc.perform(post(
                "/api/v1/core/engagements/{id}/configurations", ENGAGEMENT)
                .with(token("user-engagement-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "validFrom":"2026-08-01",
                      "timezone":"Asia/Kolkata",
                      "planningDueDay":5,
                      "certificationDueDay":25,
                      "confirmationDueDay":27,
                      "reopenPolicy":{"reasonRequired":true},
                      "notificationPolicy":{"recipientSnapshotRequired":true},
                      "expectedEngagementVersion":0
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.version").value(2));

        mvc.perform(get(
                "/api/v1/core/engagements/{id}/configurations/effective",
                ENGAGEMENT)
                .param("effectiveOn", "2026-07-01")
                .with(token("user-engagement-admin")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value(1));
        mvc.perform(get(
                "/api/v1/core/engagements/{id}/configurations/effective",
                ENGAGEMENT)
                .param("effectiveOn", "2026-09-01")
                .with(token("user-engagement-admin")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value(2));

        mvc.perform(post(
                "/api/v1/core/engagements/{id}/configurations", ENGAGEMENT)
                .with(token("user-engagement-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "validFrom":"2026-07-15",
                      "validTo":"2026-08-15",
                      "timezone":"Asia/Kolkata",
                      "reopenPolicy":{},
                      "notificationPolicy":{},
                      "expectedEngagementVersion":1
                    }
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code")
                .value("CONFIGURATION_EFFECTIVE_WINDOW_CONFLICT"));
    }

    @Test
    void contactPolicyStageAndQuorumAreValidatedBeforePublication()
        throws Exception {
        String groupBody = mvc.perform(post(
                "/api/v1/core/engagements/{id}/contact-groups", ENGAGEMENT)
                .with(token("user-engagement-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "code":"CLIENT_APPROVERS_TEST",
                      "name":"Client approvers",
                      "groupType":"CLIENT_APPROVERS"
                    }
                    """))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String groupId = objectId(groupBody);

        mvc.perform(post(
                "/api/v1/core/contact-groups/{id}/members", groupId)
                .with(token("user-engagement-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "userProfileId":"%s",
                      "email":"admin@reliance.example",
                      "displayName":"Reliance Engagement Admin",
                      "roleAttribution":"CLIENT_APPROVER",
                      "verified":true,
                      "validFrom":"2026-01-01",
                      "expectedGroupVersion":0
                    }
                    """.formatted(ADMIN_USER)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.version").value(1));

        String policyBody = mvc.perform(post(
                "/api/v1/core/engagements/{id}/approval-policies", ENGAGEMENT)
                .with(token("user-engagement-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "code":"REOPEN_TEST",
                      "name":"Reopen approval",
                      "actionType":"REOPEN",
                      "validFrom":"2026-08-01",
                      "prohibitSelfApproval":true,
                      "evidenceRequired":true,
                      "rules":{"separationOfDuties":true},
                      "stages":[{
                        "name":"Client approval",
                        "contactGroupId":"%s",
                        "quorumMode":"ANY_ONE",
                        "quorumRequired":1,
                        "allowDelegation":true
                      }]
                    }
                    """.formatted(groupId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.stages.length()").value(1))
            .andReturn().getResponse().getContentAsString();
        String policyId = objectId(policyBody);

        mvc.perform(post(
                "/api/v1/core/approval-policies/{id}/publish", policyId)
                .with(token("user-engagement-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedPolicyVersion\":0}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.versionStatus").value("PUBLISHED"));

        mvc.perform(post(
                "/api/v1/core/engagements/{id}/approval-policies", ENGAGEMENT)
                .with(token("user-engagement-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "code":"IMPOSSIBLE_TEST",
                      "name":"Impossible policy",
                      "actionType":"REOPEN",
                      "validFrom":"2027-01-01",
                      "prohibitSelfApproval":true,
                      "evidenceRequired":true,
                      "rules":{},
                      "stages":[{
                        "name":"Impossible quorum",
                        "contactGroupId":"%s",
                        "quorumMode":"N_OF_M",
                        "quorumRequired":2,
                        "allowDelegation":false
                      }]
                    }
                    """.formatted(groupId)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void eligibleUsersAndDelegationsRemainParticipantAndAuthorityScoped()
        throws Exception {
        mvc.perform(get(
                "/api/v1/core/engagements/{id}/eligible-users", ENGAGEMENT)
                .param("organizationId", RELIANCE_ORG)
                .with(token("user-engagement-admin")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == '%s')]".formatted(
                DELEGATE_USER)).exists())
            .andExpect(jsonPath("$[0].password").doesNotExist());

        mvc.perform(get(
                "/api/v1/core/engagements/{id}/eligible-users", ENGAGEMENT)
                .param("organizationId", NORTHSTAR_ORG)
                .with(token("user-engagement-admin")))
            .andExpect(status().isNotFound());

        OffsetDateTime from = OffsetDateTime.now().plusDays(1);
        OffsetDateTime to = from.plusDays(14);
        String delegationBody = mvc.perform(post(
                "/api/v1/core/engagements/{id}/delegations", ENGAGEMENT)
                .with(token("user-engagement-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "organizationId":"%s",
                      "delegatorUserId":"%s",
                      "delegateUserId":"%s",
                      "actionCodes":["contacts.manage"],
                      "validFrom":"%s",
                      "validTo":"%s",
                      "reason":"Planned absence coverage"
                    }
                    """.formatted(
                    RELIANCE_ORG, ADMIN_USER, DELEGATE_USER, from, to)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andReturn().getResponse().getContentAsString();
        String delegationId = objectId(delegationBody);

        mvc.perform(post(
                "/api/v1/core/delegations/{id}/revoke", delegationId)
                .with(token("user-engagement-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"reason":"Coverage no longer required","expectedVersion":0}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("REVOKED"))
            .andExpect(jsonPath("$.version").value(1));

        mvc.perform(post(
                "/api/v1/core/engagements/{id}/delegations", ENGAGEMENT)
                .with(token("user-engagement-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "organizationId":"%s",
                      "delegatorUserId":"%s",
                      "delegateUserId":"%s",
                      "actionCodes":["procurement.exception"],
                      "validFrom":"%s",
                      "validTo":"%s",
                      "reason":"Forged authority"
                    }
                    """.formatted(
                    RELIANCE_ORG, ADMIN_USER, DELEGATE_USER, from, to)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void futurePolicyRevisionPreservesCurrentEffectiveAuthorization()
        throws Exception {
        String policyId = createAndPublishPolicy(
            "REVISION_TEST", null, true,
            """
                [{
                  "name":"Initial authority",
                  "explicitAssigneeId":"%s",
                  "quorumMode":"ANY_ONE",
                  "quorumRequired":1,
                  "allowDelegation":true
                }]
                """.formatted(REOPEN_AUTHORITY));
        mvc.perform(post(
                "/api/v1/core/approval-policies/{id}/revisions", policyId)
                .with(token("user-engagement-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "name":"Revised reopen authority",
                      "validFrom":"2027-01-01",
                      "prohibitSelfApproval":true,
                      "evidenceRequired":true,
                      "rules":{"authoritySnapshotRequired":true},
                      "stages":[{
                        "name":"Revised authority",
                        "explicitAssigneeId":"%s",
                        "quorumMode":"ANY_ONE",
                        "quorumRequired":1,
                        "allowDelegation":true
                      }],
                      "expectedPolicyVersion":1
                    }
                    """.formatted(REOPEN_AUTHORITY)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(policyId))
            .andExpect(jsonPath("$.policyVersion").value(2))
            .andExpect(jsonPath("$.versionStatus").value("DRAFT"))
            .andExpect(jsonPath("$.version").value(2));
        String beforePublishRequestId = objectId(createApprovalRequest(
            policyId, null, UUID.randomUUID(), "revision-before-publish",
            "0".repeat(64)));
        mvc.perform(post(
                "/api/v1/core/approval-policies/{id}/publish", policyId)
                .with(token("user-engagement-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedPolicyVersion\":2}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.policyVersion").value(2))
            .andExpect(jsonPath("$.versionStatus").value("PUBLISHED"));
        String afterPublishRequestId = objectId(createApprovalRequest(
            policyId, null, UUID.randomUUID(), "revision-before-effective",
            "1".repeat(64)));
        assertTrue(jdbc.queryForObject("""
            SELECT count(*) FILTER (WHERE status = 'PUBLISHED') = 2
               AND count(*) FILTER (
                   WHERE status = 'PUBLISHED'
                     AND valid_to = DATE '2026-12-31') = 1
            FROM approval_policy_versions
            WHERE policy_id = ?::uuid
            """, Boolean.class, policyId));
        assertTrue(jdbc.queryForObject("""
            SELECT count(DISTINCT policy_version_id) = 1
               AND max(version.version) = 1
            FROM core_approval_requests request
            JOIN approval_policy_versions version
              ON version.id = request.policy_version_id
            WHERE request.id IN (?::uuid, ?::uuid)
            """, Boolean.class,
            beforePublishRequestId, afterPublishRequestId));
    }

    @Test
    void allQuorumUsesRequestTimeAuthoritySnapshotAndEvidencePolicy()
        throws Exception {
        String groupId = createContactGroup(
            "ALL_DYNAMIC", ADMIN_USER, RELIANCE_USER);
        String policyId = createAndPublishPolicy(
            "ALL_DYNAMIC_REOPEN", null, false,
            """
                [{
                  "name":"All current authorities",
                  "contactGroupId":"%s",
                  "quorumMode":"ALL",
                  "quorumRequired":2,
                  "allowDelegation":true
                }]
                """.formatted(groupId));
        mvc.perform(post(
                "/api/v1/core/contact-groups/{id}/members", groupId)
                .with(token("user-engagement-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "userProfileId":"%s",
                      "email":"reopen-authority@reliance.example",
                      "displayName":"Reopen Authority",
                      "roleAttribution":"CLIENT_APPROVER",
                      "verified":true,
                      "validFrom":"2026-01-01",
                      "expectedGroupVersion":2
                    }
                    """.formatted(REOPEN_AUTHORITY)))
            .andExpect(status().isCreated());
        String requestBody = createApprovalRequest(
            policyId, null, UUID.randomUUID(), "all-dynamic-request",
            "2".repeat(64));
        String requestId = objectId(requestBody);
        assertTrue(requestBody.contains("\"quorumRequired\":3"));
        assertTrue(jdbc.queryForObject("""
            SELECT quorum_mode = 'ALL'
               AND quorum_required = 3
               AND cardinality(eligible_user_ids) = 3
            FROM core_approval_stage_snapshots
            WHERE request_id = ?::uuid AND stage_order = 1
            """, Boolean.class, requestId));
        mvc.perform(post(
                "/api/v1/core/approval-requests/{id}/actions", requestId)
                .with(token("user-engagement-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "decision":"APPROVED",
                      "reason":" ",
                      "idempotencyKey":"all-empty-evidence",
                      "expectedRequestVersion":0
                    }
                    """))
            .andExpect(status().isBadRequest());
        mvc.perform(post(
                "/api/v1/core/approval-requests/{id}/actions", requestId)
                .with(token("user-engagement-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(actionJson("APPROVED", null, 0)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(post(
                "/api/v1/core/approval-requests/{id}/actions", requestId)
                .with(token("user-reliance"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(actionJson("APPROVED", null, 1)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(post(
                "/api/v1/core/approval-requests/{id}/actions", requestId)
                .with(token("user-reopen-authority"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(actionJson("APPROVED", null, 2)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void approvalQuorumCountsAuthorityOnceAndEnforcesReplayAndVersions()
        throws Exception {
        String groupId = createContactGroup(
            "QUORUM_3", ADMIN_USER, RELIANCE_USER, NAM_PRODUCT_OWNER);
        String policyId = createAndPublishPolicy(
            "PLAN_QUORUM_3", null, false,
            """
                [{
                  "name":"Two of three client authorities",
                  "contactGroupId":"%s",
                  "quorumMode":"N_OF_M",
                  "quorumRequired":2,
                  "allowDelegation":true
                }]
                """.formatted(groupId));
        UUID objectId = UUID.randomUUID();
        String requestBody = createApprovalRequest(
            policyId, null, objectId, "quorum-idempotency",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        String requestId = objectId(requestBody);
        assertTrue(jdbc.queryForObject("""
            SELECT cardinality(eligible_user_ids) = 3
               AND contact_group_version = 3
            FROM core_approval_stage_snapshots
            WHERE request_id = ?::uuid AND stage_order = 1
            """, Boolean.class, requestId));
        mvc.perform(post(
                "/api/v1/core/contact-groups/{id}/members", groupId)
                .with(token("user-engagement-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "userProfileId":"%s",
                      "email":"late-delegate@example.test",
                      "displayName":"Late delegate",
                      "roleAttribution":"CLIENT_APPROVER",
                      "verified":true,
                      "validFrom":"2026-01-01",
                      "expectedGroupVersion":3
                    }
                    """.formatted(DELEGATE_USER)))
            .andExpect(status().isCreated());
        mvc.perform(post(
                "/api/v1/core/approval-requests/{id}/actions", requestId)
                .with(token("user-reliance-delegate"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(actionJson("APPROVED", null, 0)))
            .andExpect(status().isForbidden());

        createApprovalRequest(
            policyId, null, objectId, "quorum-idempotency",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        mvc.perform(post(
                "/api/v1/core/engagements/{id}/approval-requests", ENGAGEMENT)
                .with(token("user-engagement-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(approvalRequestJson(
                    policyId, null, UUID.randomUUID(),
                    "quorum-idempotency",
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code")
                .value("APPROVAL_IDEMPOTENCY_KEY_REUSED"));

        mvc.perform(post(
                "/api/v1/core/approval-requests/{id}/actions", requestId)
                .with(token("user-project-reader"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(actionJson("APPROVED", null, 0)))
            .andExpect(status().isNotFound());

        mvc.perform(post(
                "/api/v1/core/approval-requests/{id}/actions", requestId)
                .with(token("user-engagement-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(actionJson("APPROVED", null, 0)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.actions.length()").value(1));

        mvc.perform(post(
                "/api/v1/core/approval-requests/{id}/actions", requestId)
                .with(token("user-reliance"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(actionJson("APPROVED", null, 0)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code")
                .value("APPROVAL_REQUEST_VERSION_CONFLICT"))
            .andExpect(jsonPath("$.currentVersion").value(1));

        String delegationId = createDelegation(
            ADMIN_USER, DELEGATE_USER, "month.transition");
        mvc.perform(post(
                "/api/v1/core/approval-requests/{id}/actions", requestId)
                .with(token("user-reliance-delegate"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(actionJson("APPROVED", delegationId, 1)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code")
                .value("APPROVAL_AUTHORITY_ALREADY_ACTED"))
            .andExpect(jsonPath("$.currentVersion").value(1));

        String finalAction = actionJson(
            "APPROVED", null, 1, "quorum-final-action");
        mvc.perform(post(
                "/api/v1/core/approval-requests/{id}/actions", requestId)
                .with(token("user-reliance"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(finalAction))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(jsonPath("$.version").value(2))
            .andExpect(jsonPath("$.actions.length()").value(2));
        mvc.perform(post(
                "/api/v1/core/approval-requests/{id}/actions", requestId)
                .with(token("user-reliance"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(finalAction))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value(2))
            .andExpect(jsonPath("$.actions.length()").value(2));
        mvc.perform(post(
                "/api/v1/core/approval-requests/{id}/actions", requestId)
                .with(token("user-reliance"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(actionJson(
                    "REJECTED", null, 1, "quorum-final-action")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code")
                .value("APPROVAL_ACTION_IDEMPOTENCY_KEY_REUSED"));
        assertTrue(jdbc.queryForObject("""
            SELECT state = 'REOPENED'
            FROM engagement_months WHERE id = ?
            """, Boolean.class, objectId));
    }

    @Test
    void approvalSelfActionAndExpiredDelegationAreRejectedButValidDelegationWorks()
        throws Exception {
        String selfPolicyId = createAndPublishPolicy(
            "SELF_GUARD", null, true,
            """
                [{
                  "name":"Named administrator",
                  "explicitAssigneeId":"%s",
                  "quorumMode":"ANY_ONE",
                  "quorumRequired":1,
                  "allowDelegation":true
                }]
                """.formatted(ADMIN_USER));
        String selfRequestId = objectId(createApprovalRequest(
            selfPolicyId, null, UUID.randomUUID(), "self-guard-key",
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"));
        mvc.perform(post(
                "/api/v1/core/approval-requests/{id}/actions", selfRequestId)
                .with(token("user-engagement-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(actionJson("APPROVED", null, 0)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code")
                .value("APPROVAL_SELF_ACTION_PROHIBITED"));
        String selfDelegationId = createDelegation(
            ADMIN_USER, DELEGATE_USER, "month.transition");
        mvc.perform(post(
                "/api/v1/core/approval-requests/{id}/actions", selfRequestId)
                .with(token("user-reliance-delegate"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(actionJson("APPROVED", selfDelegationId, 0)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code")
                .value("APPROVAL_SELF_ACTION_PROHIBITED"));

        String delegatedPolicyId = createAndPublishPolicy(
            "DELEGATED_PLAN", null, true,
            """
                [{
                  "name":"Reliance owner",
                  "explicitAssigneeId":"%s",
                  "quorumMode":"ANY_ONE",
                  "quorumRequired":1,
                  "allowDelegation":true
                }]
                """.formatted(REOPEN_AUTHORITY));
        String delegatedRequestId = objectId(createApprovalRequest(
            delegatedPolicyId, null, UUID.randomUUID(), "delegated-key",
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"));
        UUID expiredDelegationId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO delegations(
                id, organization_id, engagement_id, delegator_user_id,
                delegate_user_id, action_codes, valid_from, valid_to,
                status, reason, created_by_subject)
            VALUES (?, ?::uuid, ?::uuid, ?::uuid, ?::uuid,
                    ARRAY['month.transition'],
                    CURRENT_TIMESTAMP - INTERVAL '2 days',
                    CURRENT_TIMESTAMP - INTERVAL '1 day',
                    'EXPIRED', 'Expired test authority', 'integration-test')
            """, expiredDelegationId, RELIANCE_ORG, ENGAGEMENT,
            REOPEN_AUTHORITY, DELEGATE_USER);
        mvc.perform(post(
                "/api/v1/core/approval-requests/{id}/actions",
                delegatedRequestId)
                .with(token("user-reliance-delegate"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(actionJson(
                    "APPROVED", expiredDelegationId.toString(), 0)))
            .andExpect(status().isForbidden());

        String validDelegationId = createDelegation(
            REOPEN_AUTHORITY, DELEGATE_USER, "month.transition");
        mvc.perform(post(
                "/api/v1/core/approval-requests/{id}/actions",
                delegatedRequestId)
                .with(token("user-reliance-delegate"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(actionJson("APPROVED", validDelegationId, 0)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(jsonPath("$.actions[0].delegatedFromUserId")
                .value(REOPEN_AUTHORITY))
            .andExpect(jsonPath("$.actions[0].delegationId")
                .value(validDelegationId));
    }

    @Test
    void projectApprovalAndCanonicalRolesRemainExactlyScoped()
        throws Exception {
        String policyId = createAndPublishPolicy(
            "SHOPOS_PROJECT_OWNER", SHOPOS_PROJECT, false,
            """
                [{
                  "name":"Project product owner",
                  "roleCode":"CLIENT_PRODUCT_OWNER",
                  "quorumMode":"ANY_ONE",
                  "quorumRequired":1,
                  "allowDelegation":false
                }]
                """);
        mvc.perform(post(
                "/api/v1/core/engagements/{id}/approval-requests", ENGAGEMENT)
                .with(token("user-engagement-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(approvalRequestJson(
                    policyId, SHOPOS_PROJECT, UUID.randomUUID(),
                    "project-scope-key",
                    "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd")))
            .andExpect(status().isBadRequest());

        UUID policyVersionId = jdbc.queryForObject("""
            SELECT current_version_id FROM approval_policies
            WHERE id = ?::uuid
            """, UUID.class, policyId);
        assertFalse(jdbc.queryForObject("""
            SELECT f01_actor_eligible_for_stage(
                ?, 1, ?::uuid, ?::uuid, ?::uuid)
            """, Boolean.class, policyVersionId, NAM_PRODUCT_OWNER,
            ENGAGEMENT, SHOPOS_PROJECT));

        Integer canonicalRoles = jdbc.queryForObject("""
            SELECT count(*) FROM roles
            WHERE code IN (
                'CLIENT_APPROVER', 'PROGRAM_GOVERNANCE',
                'INTEGRATION_ADMIN', 'SUPPORT_OPERATOR', 'SERVICE_ACCOUNT')
            """, Integer.class);
        assertTrue(canonicalRoles != null && canonicalRoles == 5);
        assertFalse(jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1
                FROM roles role
                JOIN role_permissions mapping ON mapping.role_id = role.id
                JOIN permissions permission
                  ON permission.id = mapping.permission_id
                WHERE role.code IN (
                    'PLATFORM_ADMIN', 'SUPPORT_OPERATOR', 'SERVICE_ACCOUNT')
                  AND permission.code = 'approval.request.act'
            )
            """, Boolean.class));
    }

    @Test
    void monthTransitionUsesExpectedVersionAndImmutableHistory()
        throws Exception {
        UUID monthId = UUID.randomUUID();
        java.sql.Date monthStart = jdbc.queryForObject("""
            SELECT (
                COALESCE(MAX(month_start_date), DATE '2026-07-01')
                + INTERVAL '1 month'
            )::date
            FROM engagement_months
            WHERE engagement_id = ?::uuid
            """, java.sql.Date.class, ENGAGEMENT);
        jdbc.update("""
            INSERT INTO engagement_months(
                id, engagement_id, month_start_date, state, risk_status)
            VALUES (?, ?::uuid, ?, 'DRAFT', 'ON_TRACK')
            """, monthId, ENGAGEMENT, monthStart);

        mvc.perform(post(
                "/api/v1/core/engagement-months/{id}/transitions", monthId)
                .with(token("user-engagement-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "targetState":"PLANNING",
                      "reason":"Planning window opened",
                      "expectedVersion":0
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fromState").value("DRAFT"))
            .andExpect(jsonPath("$.toState").value("PLANNING"))
            .andExpect(jsonPath("$.toVersion").value(1))
            .andExpect(jsonPath("$.actorSubject")
                .value("user-engagement-admin"));

        mvc.perform(post(
                "/api/v1/core/engagement-months/{id}/transitions", monthId)
                .with(token("user-engagement-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "targetState":"CLOSED",
                      "reason":"Invalid shortcut",
                      "expectedVersion":1
                    }
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("INVALID_MONTH_TRANSITION"));

        mvc.perform(get(
                "/api/v1/core/engagement-months/{id}/transitions", monthId)
                .with(token("user-engagement-admin")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void databaseRejectsCrossScopePointersAndArbitraryStateTransitions() {
        UUID foreignConfigurationId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO engagement_configuration_versions(
                id, engagement_id, version, status, valid_from, timezone,
                created_by_subject, published_at)
            VALUES (?, ?::uuid, 1, 'PUBLISHED', DATE '2026-07-01', 'UTC',
                    'integration-test', CURRENT_TIMESTAMP)
            """, foreignConfigurationId, NORTHSTAR_ENGAGEMENT);
        assertThrows(RuntimeException.class, () -> jdbc.update("""
            UPDATE engagements
            SET configuration_version_id = ?
            WHERE id = ?::uuid
            """, foreignConfigurationId, ENGAGEMENT));
    }

    @Test
    void publishedVersionsAndApprovalEvidenceAreRuntimeProtected()
        throws Exception {
        UUID configurationId = jdbc.queryForObject("""
            SELECT configuration_version_id FROM engagements
            WHERE id = ?::uuid
            """, UUID.class, ENGAGEMENT);
        assertThrows(RuntimeException.class, () -> jdbc.update("""
            UPDATE engagement_configuration_versions
            SET timezone = 'UTC' WHERE id = ?
            """, configurationId));
    }

    @Test
    void databaseRejectsApprovalStatusBypassWithoutBoundAction()
        throws Exception {
        String policyId = createAndPublishPolicy(
            "REQUEST_BYPASS", null, false,
            """
                [{
                  "name":"Reopen authority",
                  "explicitAssigneeId":"%s",
                  "quorumMode":"ANY_ONE",
                  "quorumRequired":1,
                  "allowDelegation":true
                }]
                """.formatted(REOPEN_AUTHORITY));
        String requestId = objectId(createApprovalRequest(
            policyId, null, UUID.randomUUID(), "request-bypass-key",
            "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"));
        assertThrows(RuntimeException.class, () -> jdbc.update("""
            UPDATE core_approval_requests
            SET status = 'APPROVED', version = version + 1
            WHERE id = ?::uuid
            """, requestId));
    }

    @Test
    void databaseRejectsBlankEvidenceOnDirectApprovalAction()
        throws Exception {
        String policyId = createAndPublishPolicy(
            "EVIDENCE_BYPASS", null, false,
            """
                [{
                  "name":"Reopen authority",
                  "explicitAssigneeId":"%s",
                  "quorumMode":"ANY_ONE",
                  "quorumRequired":1,
                  "allowDelegation":true
                }]
                """.formatted(REOPEN_AUTHORITY));
        String requestId = objectId(createApprovalRequest(
            policyId, null, UUID.randomUUID(), "evidence-bypass-key",
            "f".repeat(64)));
        assertThrows(RuntimeException.class, () -> jdbc.update("""
            INSERT INTO core_approval_actions(
                id, request_id, request_version, stage_order, decision,
                actor_user_id, actor_subject, authority_snapshot,
                idempotency_key, source, reason)
            VALUES (?, ?::uuid, 1, 1, 'APPROVED', ?::uuid,
                    'user-reopen-authority', '{}'::jsonb,
                    'direct-empty-evidence', 'IN_APP', ' ')
            """, UUID.randomUUID(), requestId, REOPEN_AUTHORITY));
    }

    @Test
    void databaseRejectsUnapprovedDirectReopenTransition() {
        UUID monthId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO engagement_months(
                id, engagement_id, month_start_date, state, risk_status,
                governance_version)
            VALUES (?, ?::uuid, DATE '2026-11-01', 'REOPEN_REQUESTED',
                    'ON_TRACK', 1)
            """, monthId, ENGAGEMENT);
        assertThrows(RuntimeException.class, () -> jdbc.update("""
            UPDATE engagement_months
            SET state = 'REOPENED', governance_version = 2
            WHERE id = ?
            """, monthId));
    }

    @Test
    void v34RuntimeAndPublicGrantsAreLeastPrivilege() {
        assertTrue(jdbc.queryForObject("""
            SELECT has_table_privilege(
                'vms_app_runtime', 'core_approval_actions', 'INSERT')
            """, Boolean.class));
        assertFalse(jdbc.queryForObject("""
            SELECT has_table_privilege(
                'vms_app_runtime',
                'engagement_configuration_versions', 'UPDATE')
            """, Boolean.class));
        assertFalse(jdbc.queryForObject("""
            SELECT has_table_privilege(
                'vms_app_runtime', 'core_approval_requests', 'DELETE')
            """, Boolean.class));
        assertFalse(jdbc.queryForObject("""
            SELECT has_table_privilege(
                'vms_app_runtime',
                'core_approval_stage_snapshots', 'UPDATE')
            """, Boolean.class));
        assertFalse(jdbc.queryForObject("""
            SELECT has_function_privilege(
                'public', 'f01_record_month_transition()', 'EXECUTE')
            """, Boolean.class));
        assertTrue(jdbc.queryForObject("""
            SELECT has_function_privilege(
                'vms_app_runtime',
                'f01_record_month_transition()', 'EXECUTE')
            """, Boolean.class));
    }

    @Test
    void openApiPublishesExecutableCoreAndErrorContracts()
        throws Exception {
        mvc.perform(get("/v3/api-docs")
                .with(token("user-engagement-admin")))
            .andExpect(status().isOk())
            .andExpect(jsonPath(
                "$.paths['/api/v1/core/engagements/{engagementId}'].patch")
                .exists())
            .andExpect(jsonPath(
                "$.paths['/api/v1/core/engagements/{engagementId}/eligible-users'].get")
                .exists())
            .andExpect(jsonPath(
                "$.paths['/api/v1/core/approval-policies/{policyId}/publish'].post")
                .exists())
            .andExpect(jsonPath(
                "$.paths['/api/v1/core/approval-policies/{policyId}/revisions'].post")
                .exists())
            .andExpect(jsonPath(
                "$.paths['/api/v1/core/engagements/{engagementId}/approval-requests'].post")
                .exists())
            .andExpect(jsonPath(
                "$.paths['/api/v1/core/approval-requests/{requestId}/actions'].post")
                .exists())
            .andExpect(jsonPath(
                "$.paths['/api/v1/core/engagement-months/{monthId}/transitions'].post")
                .exists())
            .andExpect(jsonPath(
                "$.components.schemas.MeView.properties.permissions")
                .exists())
            .andExpect(jsonPath(
                "$.components.schemas.CreateApprovalPolicyInput.properties.stages")
                .exists())
            .andExpect(jsonPath(
                "$.components.schemas.ApprovalStageInput.properties.quorumMode")
                .exists())
            .andExpect(jsonPath(
                "$.components.schemas.CreateApprovalRequestInput.properties.objectType")
                .doesNotExist())
            .andExpect(jsonPath(
                "$.components.schemas.CreateApprovalRequestInput.properties.objectHash")
                .doesNotExist())
            .andExpect(jsonPath(
                "$.components.schemas.ApprovalActionInput.properties.idempotencyKey")
                .exists());
    }

    private String createContactGroup(String code, String... userIds)
        throws Exception {
        String groupBody = mvc.perform(post(
                "/api/v1/core/engagements/{id}/contact-groups", ENGAGEMENT)
                .with(token("user-engagement-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "code":"%s",
                      "name":"%s",
                      "groupType":"CLIENT_APPROVERS"
                    }
                    """.formatted(code, code)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String groupId = objectId(groupBody);
        for (int index = 0; index < userIds.length; index++) {
            String userId = userIds[index];
            mvc.perform(post(
                    "/api/v1/core/contact-groups/{id}/members", groupId)
                    .with(token("user-engagement-admin"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "userProfileId":"%s",
                          "email":"approver-%s@example.test",
                          "displayName":"Approval authority %s",
                          "roleAttribution":"CLIENT_APPROVER",
                          "verified":true,
                          "validFrom":"2026-01-01",
                          "expectedGroupVersion":%d
                        }
                        """.formatted(userId, index, index, index)))
                .andExpect(status().isCreated());
        }
        return groupId;
    }

    private String createAndPublishPolicy(
        String code,
        String projectId,
        boolean prohibitSelfApproval,
        String stagesJson
    ) throws Exception {
        String projectJson = projectId == null
            ? "null" : "\"" + projectId + "\"";
        String body = mvc.perform(post(
                "/api/v1/core/engagements/{id}/approval-policies", ENGAGEMENT)
                .with(token("user-engagement-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "projectId":%s,
                      "code":"%s",
                      "name":"%s",
                      "actionType":"REOPEN",
                      "validFrom":"2026-01-01",
                      "prohibitSelfApproval":%s,
                      "evidenceRequired":true,
                      "rules":{"authorityIdentityQuorum":true},
                      "stages":%s
                    }
                    """.formatted(
                    projectJson, code, code, prohibitSelfApproval,
                    stagesJson)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String policyId = objectId(body);
        mvc.perform(post(
                "/api/v1/core/approval-policies/{id}/publish", policyId)
                .with(token("user-engagement-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedPolicyVersion\":0}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.versionStatus").value("PUBLISHED"));
        return policyId;
    }

    private String createApprovalRequest(
        String policyId,
        String projectId,
        UUID objectId,
        String idempotencyKey,
        String objectHash
    ) throws Exception {
        jdbc.update("""
            INSERT INTO engagement_months(
                id, engagement_id, month_start_date, state, risk_status,
                governance_version)
            SELECT ?, ?::uuid,
                   (COALESCE(max(month_start_date), DATE '2026-09-01')
                       + INTERVAL '1 month')::date,
                   'REOPEN_REQUESTED', 'ON_TRACK', 1
            FROM engagement_months
            WHERE engagement_id = ?::uuid
            ON CONFLICT (id) DO NOTHING
            """, objectId, ENGAGEMENT, ENGAGEMENT);
        return mvc.perform(post(
                "/api/v1/core/engagements/{id}/approval-requests", ENGAGEMENT)
                .with(token("user-engagement-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(approvalRequestJson(
                    policyId, projectId, objectId, idempotencyKey,
                    objectHash)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
    }

    private String createDelegation(
        String delegatorUserId,
        String delegateUserId,
        String actionCode
    ) throws Exception {
        OffsetDateTime from = OffsetDateTime.now().minusHours(1);
        OffsetDateTime to = OffsetDateTime.now().plusDays(2);
        String body = mvc.perform(post(
                "/api/v1/core/engagements/{id}/delegations", ENGAGEMENT)
                .with(token("user-engagement-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "organizationId":"%s",
                      "delegatorUserId":"%s",
                      "delegateUserId":"%s",
                      "actionCodes":["%s"],
                      "validFrom":"%s",
                      "validTo":"%s",
                      "reason":"Approval test coverage"
                    }
                    """.formatted(
                    RELIANCE_ORG, delegatorUserId, delegateUserId,
                    actionCode, from, to)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectId(body);
    }

    private static String approvalRequestJson(
        String policyId,
        String projectId,
        UUID objectId,
        String idempotencyKey,
        String objectHash
    ) {
        return """
            {
              "policyId":"%s",
              "objectId":"%s",
              "idempotencyKey":"%s"
            }
            """.formatted(
            policyId, objectId, idempotencyKey);
    }

    private static String actionJson(
        String decision,
        String delegationId,
        long expectedVersion
    ) {
        return actionJson(
            decision, delegationId, expectedVersion,
            "action-" + UUID.randomUUID());
    }

    private static String actionJson(
        String decision,
        String delegationId,
        long expectedVersion,
        String idempotencyKey
    ) {
        String delegationJson = delegationId == null
            ? "null" : "\"" + delegationId + "\"";
        return """
            {
              "decision":"%s",
              "reason":"Integration approval evidence",
              "delegationId":%s,
              "idempotencyKey":"%s",
              "expectedRequestVersion":%d
            }
            """.formatted(
            decision, delegationJson, idempotencyKey, expectedVersion);
    }

    private static String objectId(String json) {
        int marker = json.indexOf("\"id\":\"") + 6;
        return json.substring(marker, json.indexOf('"', marker));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor
    token(String subject) {
        return jwt().jwt(value ->
            value.subject(subject).audience(List.of("vms-api")));
    }
}
