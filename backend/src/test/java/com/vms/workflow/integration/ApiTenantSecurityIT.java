package com.vms.workflow.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:tc:vmspostgresql:18-alpine:///vms_workflow_api_tenant_security_it",
    "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.flyway.locations=classpath:db/migration,classpath:db/testdata",
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:9/test-jwks",
    "vms.security.issuer=https://issuer.example.test",
    "vms.security.audience=vms-api"
})
@AutoConfigureMockMvc
class ApiTenantSecurityIT {
    private static final String RELIANCE_ORG = "00000000-0000-0000-0000-000000000102";
    private static final String NORTHSTAR_ORG = "00000000-0000-0000-0000-000000000104";
    private static final String RELIANCE_ENGAGEMENT = "00000000-0000-0000-0000-000000000401";
    private static final String NORTHSTAR_ENGAGEMENT = "00000000-0000-0000-0000-000000000402";
    private static final String RELIANCE_PROJECT = "00000000-0000-0000-0000-000000000501";
    private static final String SECOND_RELIANCE_PROJECT = "00000000-0000-0000-0000-000000000502";
    private static final String NORTHSTAR_PROJECT = "00000000-0000-0000-0000-000000000503";
    private static final String NORTHSTAR_MONTH = "00000000-0000-0000-0000-000000000603";
    private static final String UNKNOWN_ID = "ffffffff-ffff-ffff-ffff-ffffffffffff";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void unauthenticatedApiRequestIsDeniedWithProblemDetails() throws Exception {
        mvc.perform(get("/api/v1/organizations"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(401))
            .andExpect(jsonPath("$.title").value("Unauthorized"));
    }

    @Test
    void activeMemberCanReadOwnOrganizationAndEngagement() throws Exception {
        mvc.perform(get("/api/v1/organizations/{id}", RELIANCE_ORG)
                .with(token("user-reliance")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("RELIANCE_INTELLIGENCE"));

        mvc.perform(get("/api/v1/engagements/{id}", RELIANCE_ENGAGEMENT)
                .with(token("user-reliance")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.engagementCode").value("RI-AF-2026"));
    }

    @Test
    void memberCannotReadCrossOrganizationOrCrossEngagement() throws Exception {
        mvc.perform(get("/api/v1/organizations/{id}", NORTHSTAR_ORG)
                .with(token("user-reliance")))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(404));

        mvc.perform(get("/api/v1/engagements/{id}", NORTHSTAR_ENGAGEMENT)
                .with(token("user-reliance")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void legacyReadInfersMembershipScopeAndFlattensPayload() throws Exception {
        mvc.perform(get("/api/v1/legacy/requirements")
                .with(token("user-reliance")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value("00000000-0000-0000-0000-000000000702"))
            .andExpect(jsonPath("$[0].title").value("Synthetic baseline requirement"))
            .andExpect(jsonPath("$[0].payload").doesNotExist());

        mvc.perform(get("/api/v1/legacy/requirements")
                .queryParam("organizationId", NORTHSTAR_ORG)
                .with(token("user-reliance")))
            .andExpect(status().isNotFound());
    }

    @Test
    void unknownDisabledAndInvalidScopeIdentitiesFailClosedAcrossEndpointFamilies() throws Exception {
        List<String> deniedSubjects = List.of(
            "unknown-subject",
            "user-disabled",
            "user-expired-membership",
            "user-inactive-membership",
            "user-future-membership",
            "user-inactive-org"
        );
        List<String> paths = List.of(
            "/api/v1/me",
            "/api/v1/organizations",
            "/api/v1/organizations/" + RELIANCE_ORG,
            "/api/v1/engagements?organizationId=" + RELIANCE_ORG,
            "/api/v1/engagements/" + RELIANCE_ENGAGEMENT,
            "/api/v1/projects?engagementId=" + RELIANCE_ENGAGEMENT,
            "/api/v1/projects/" + RELIANCE_PROJECT,
            "/api/v1/engagement-months?engagementId=" + RELIANCE_ENGAGEMENT,
            "/api/v1/engagement-months/00000000-0000-0000-0000-000000000601",
            "/api/v1/legacy/requirements"
        );

        for (String subject : deniedSubjects) {
            for (String path : paths) {
                mvc.perform(get(path).with(token(subject)))
                    .andExpect(status().isForbidden());
            }
        }
    }

    @Test
    void catalogPermissionIsDenyByDefaultAndHonorsProjectScope() throws Exception {
        mvc.perform(get("/api/v1/organizations").with(token("user-wrong-role")))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/legacy/requirements").with(token("user-expired-role")))
            .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/me").with(token("user-project-reader")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.memberships.length()").value(1))
            .andExpect(jsonPath("$.memberships[0].organizationId").value(RELIANCE_ORG));
        mvc.perform(get("/api/v1/organizations").with(token("user-project-reader")))
            .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/projects/{id}", RELIANCE_PROJECT)
                .with(token("user-project-reader")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.projectCode").value("NAM"));
        mvc.perform(get("/api/v1/projects/{id}", SECOND_RELIANCE_PROJECT)
                .with(token("user-project-reader")))
            .andExpect(status().isNotFound());
    }

    @Test
    void projectListsRespectProjectScopeWithoutDisclosingOtherEngagements() throws Exception {
        mvc.perform(get("/api/v1/projects")
                .queryParam("engagementId", RELIANCE_ENGAGEMENT)
                .with(token("user-project-reader")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(RELIANCE_PROJECT));

        mvc.perform(get("/api/v1/projects")
                .queryParam("engagementId", RELIANCE_ENGAGEMENT)
                .with(token("user-reliance")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2));

        mvc.perform(get("/api/v1/projects")
                .queryParam("engagementId", NORTHSTAR_ENGAGEMENT)
                .with(token("user-project-reader")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Resource not found."));
        mvc.perform(get("/api/v1/projects")
                .queryParam("engagementId", UNKNOWN_ID)
                .with(token("user-project-reader")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Resource not found."));
    }

    @Test
    void inaccessibleAndUnknownIdsHaveUniformNotFoundResponses() throws Exception {
        assertUniformNotFound("/api/v1/organizations/" + NORTHSTAR_ORG,
            "/api/v1/organizations/" + UNKNOWN_ID);
        assertUniformNotFound("/api/v1/engagements/" + NORTHSTAR_ENGAGEMENT,
            "/api/v1/engagements/" + UNKNOWN_ID);
        assertUniformNotFound("/api/v1/projects/" + NORTHSTAR_PROJECT,
            "/api/v1/projects/" + UNKNOWN_ID);
        assertUniformNotFound("/api/v1/engagement-months/" + NORTHSTAR_MONTH,
            "/api/v1/engagement-months/" + UNKNOWN_ID);
    }

    @Test
    void databaseRejectsCrossEngagementProjectParent() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
            INSERT INTO projects
                (id, engagement_id, project_code, name, parent_project_id, start_date, status)
            VALUES (?, ?, 'ILLEGAL_CHILD', 'Illegal cross-tenant child', ?, DATE '2026-07-01', 'ACTIVE')
            """,
            UUID.fromString("00000000-0000-0000-0000-000000000599"),
            UUID.fromString(NORTHSTAR_ENGAGEMENT),
            UUID.fromString(RELIANCE_PROJECT)));
    }

    @Test
    void databaseRejectsInvalidRoleAssignmentScopes() {
        assertInvalidRoleAssignment(
            "13000000-0000-0000-0000-000000000001", "ORGANIZATION", NORTHSTAR_ORG);
        assertInvalidRoleAssignment(
            "13000000-0000-0000-0000-000000000002", "ENGAGEMENT", NORTHSTAR_ENGAGEMENT);
        assertInvalidRoleAssignment(
            "13000000-0000-0000-0000-000000000003", "PROJECT", NORTHSTAR_PROJECT);
        assertInvalidRoleAssignment(
            "13000000-0000-0000-0000-000000000004", "PROJECT", UNKNOWN_ID);
    }

    @Test
    void openApiDeclaresJwtBearerSecurity() throws Exception {
        mvc.perform(get("/v3/api-docs").with(token("user-reliance")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
            .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
            .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"))
            .andExpect(jsonPath("$.security[0].bearerAuth").isArray());
    }

    private void assertUniformNotFound(String inaccessible, String unknown) throws Exception {
        mvc.perform(get(inaccessible).with(token("user-reliance")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Resource not found."));
        mvc.perform(get(unknown).with(token("user-reliance")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Resource not found."));
    }

    private void assertInvalidRoleAssignment(String id, String scopeType, String scopeId) {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
            INSERT INTO role_assignments
                (id, user_profile_id, organization_id, role_id, scope_type, scope_id,
                 status, valid_from, valid_to)
            VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', DATE '2020-01-01', NULL)
            """,
            UUID.fromString(id),
            UUID.fromString("00000000-0000-0000-0000-000000000210"),
            UUID.fromString(RELIANCE_ORG),
            UUID.fromString("11000000-0000-0000-0000-000000000005"),
            scopeType,
            UUID.fromString(scopeId)));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor token(String subject) {
        return jwt().jwt(jwt -> jwt.subject(subject).audience(List.of("vms-api")));
    }
}
