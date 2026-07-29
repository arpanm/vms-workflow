package com.vms.workflow.integration;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.vms.workflow.integration.F04TestSupport.token;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    "vms.security.audience=vms-api"
})
@AutoConfigureMockMvc
class F07FeatureFlagObservabilityIT {
    private static final UUID NORTHSTAR =
        UUID.fromString("00000000-0000-0000-0000-000000000104");
    private static final UUID ARROW =
        UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID NORTHSTAR_ENGAGEMENT =
        UUID.fromString("00000000-0000-0000-0000-000000000402");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MeterRegistry meters;

    @BeforeEach
    void grantExplicitPlatformAuthority() {
        jdbc.update("""
            INSERT INTO f07_platform_role_assignments(
                id, user_profile_id, role_id, status, valid_from,
                assigned_by_subject
            ) VALUES (
                '27000000-0000-0000-0000-000000000001',
                '00000000-0000-0000-0000-000000000222',
                '11000000-0000-0000-0000-000000000090',
                'ACTIVE', DATE '2020-01-01', 'test-provisioner'
            ) ON CONFLICT DO NOTHING
            """);
        jdbc.update("""
            INSERT INTO role_assignments(
                id, user_profile_id, organization_id, role_id,
                scope_type, scope_id, status, valid_from
            ) VALUES (
                '27000000-0000-0000-0000-000000000002',
                '00000000-0000-0000-0000-000000000201',
                ?, '11000000-0000-0000-0000-000000000001',
                'ORGANIZATION', ?, 'ACTIVE', DATE '2020-01-01'
            ) ON CONFLICT DO NOTHING
            """, ARROW, ARROW);
    }

    @Test
    void scopeDependencyWindowAndAuditAreServerAuthoritative()
        throws Exception {
        define("feature.dependency", false);
        define("feature.main", false);
        version(
            "user-governance", "feature.dependency", """
                {
                  "scopeType":"SYSTEM",
                  "enabled":true,
                  "effectiveFrom":"2020-01-01T00:00:00Z",
                  "dependencies":[],
                  "reason":"platform prerequisite"
                }
                """, 1);
        version(
            "user-northstar", "feature.main", """
                {
                  "scopeType":"ORGANIZATION",
                  "organizationId":"%s",
                  "enabled":true,
                  "effectiveFrom":"2020-01-01T00:00:00Z",
                  "dependencies":["feature.dependency"],
                  "reason":"northstar canary"
                }
                """.formatted(NORTHSTAR), 1);

        mvc.perform(get(
                "/api/v1/governance/feature-flags/feature.main/evaluation")
                .queryParam("organizationId", NORTHSTAR.toString())
                .queryParam("engagementId", NORTHSTAR_ENGAGEMENT.toString())
                .with(token("user-northstar")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.source").value("ORGANIZATION"))
            .andExpect(jsonPath("$.authorizationGranted").value(false));

        version(
            "user-governance", "feature.dependency", """
                {
                  "scopeType":"SYSTEM",
                  "enabled":false,
                  "effectiveFrom":"2020-01-01T00:00:00Z",
                  "dependencies":[],
                  "reason":"rollback prerequisite"
                }
                """, 2);
        mvc.perform(get(
                "/api/v1/governance/feature-flags/feature.main/evaluation")
                .queryParam("organizationId", NORTHSTAR.toString())
                .with(token("user-northstar")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(false))
            .andExpect(jsonPath("$.source")
                .value("DEPENDENCY_UNSATISFIED"));

        assertEquals(5, jdbc.queryForObject("""
            SELECT count(*) FROM f07_feature_flag_transitions
            WHERE flag_id IN (
                SELECT id FROM f07_feature_flags
                WHERE flag_key IN ('feature.main', 'feature.dependency'))
            """, Integer.class));
        assertThrows(DataAccessException.class, () -> jdbc.update("""
            UPDATE f07_feature_flag_versions SET enabled = TRUE
            WHERE flag_id = (
                SELECT id FROM f07_feature_flags
                WHERE flag_key = 'feature.dependency')
            """));
    }

    @Test
    void directClientInputCannotCreateAuthorityOrBypassRbac()
        throws Exception {
        mvc.perform(post("/api/v1/governance/feature-flags")
                .with(token("user-arrow"))
                .header("Idempotency-Key", "forged-authority")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "key":"forged.authority",
                      "owner":"attacker",
                      "defaultEnabled":true,
                      "description":"attempted direct API bypass",
                      "reason":"client says allowed",
                      "authorityGranted":true,
                      "createdBySubject":"user-governance"
                    }
                    """))
            .andExpect(status().isForbidden());
        assertEquals(0, jdbc.queryForObject("""
            SELECT count(*) FROM f07_feature_flags
            WHERE flag_key = 'forged.authority'
            """, Integer.class));

        define("feature.expired", false);
        version(
            "user-northstar", "feature.expired", """
                {
                  "scopeType":"ORGANIZATION",
                  "organizationId":"%s",
                  "enabled":true,
                  "effectiveFrom":"2020-01-01T00:00:00Z",
                  "effectiveUntil":"2021-01-01T00:00:00Z",
                  "dependencies":[],
                  "reason":"expired pilot"
                }
                """.formatted(NORTHSTAR), 1);
        mvc.perform(get(
                "/api/v1/governance/feature-flags/feature.expired/evaluation")
                .queryParam("organizationId", NORTHSTAR.toString())
                .queryParam("enabled", "true")
                .with(token("user-northstar")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(false))
            .andExpect(jsonPath("$.source").value("DEFAULT"));
    }

    @Test
    void engagementFlagCannotCrossFromOnePartyOrganizationToAnother()
        throws Exception {
        define("feature.party.boundary", false);
        mvc.perform(post(
                "/api/v1/governance/feature-flags/{key}/versions",
                "feature.party.boundary")
                .with(token("user-arrow"))
                .header("Idempotency-Key", "party-boundary-forged")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "scopeType":"ENGAGEMENT",
                      "organizationId":"%s",
                      "engagementId":"%s",
                      "enabled":true,
                      "effectiveFrom":"2020-01-01T00:00:00Z",
                      "dependencies":[],
                      "reason":"cross-party write attempt"
                    }
                    """.formatted(NORTHSTAR, NORTHSTAR_ENGAGEMENT)))
            .andExpect(status().isForbidden());
        assertEquals(0, jdbc.queryForObject("""
            SELECT count(*) FROM f07_feature_flag_versions version
            JOIN f07_feature_flags flag ON flag.id = version.flag_id
            WHERE flag.flag_key = 'feature.party.boundary'
            """, Integer.class));

        mvc.perform(get(
                "/api/v1/governance/feature-flags/feature.party.boundary/evaluation")
                .queryParam("organizationId", NORTHSTAR.toString())
                .queryParam("engagementId", NORTHSTAR_ENGAGEMENT.toString())
                .with(token("user-arrow")))
            .andExpect(status().isForbidden());
    }

    @Test
    void transitiveDependencyCyclesAreRejectedBeforePersistence()
        throws Exception {
        define("feature.cycle.a", false);
        define("feature.cycle.b", false);
        version(
            "user-governance", "feature.cycle.a", """
                {
                  "scopeType":"SYSTEM",
                  "enabled":true,
                  "effectiveFrom":"2020-01-01T00:00:00Z",
                  "dependencies":["feature.cycle.b"],
                  "reason":"first dependency edge"
                }
                """, 1);

        mvc.perform(post(
                "/api/v1/governance/feature-flags/{key}/versions",
                "feature.cycle.b")
                .with(token("user-governance"))
                .header("Idempotency-Key", "cycle-b-attempt")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "scopeType":"SYSTEM",
                      "enabled":true,
                      "effectiveFrom":"2020-01-01T00:00:00Z",
                      "dependencies":["feature.cycle.a"],
                      "reason":"cycle attempt"
                    }
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code")
                .value("FEATURE_FLAG_DEPENDENCY_CYCLE"));
        assertEquals(0, jdbc.queryForObject("""
            SELECT count(*)
            FROM f07_feature_flag_versions version
            JOIN f07_feature_flags flag ON flag.id = version.flag_id
            WHERE flag.flag_key = 'feature.cycle.b'
            """, Integer.class));
    }

    @Test
    void futureVersionCannotMaskAnExistingDependencyEdge()
        throws Exception {
        define("feature.window.a", false);
        define("feature.window.b", false);
        version(
            "user-governance", "feature.window.a", """
                {
                  "scopeType":"SYSTEM",
                  "enabled":true,
                  "effectiveFrom":"2020-01-01T00:00:00Z",
                  "dependencies":["feature.window.b"],
                  "reason":"current edge"
                }
                """, 1);
        version(
            "user-governance", "feature.window.a", """
                {
                  "scopeType":"SYSTEM",
                  "enabled":true,
                  "effectiveFrom":"2099-01-01T00:00:00Z",
                  "dependencies":[],
                  "reason":"future edge replacement"
                }
                """, 2);

        mvc.perform(post(
                "/api/v1/governance/feature-flags/{key}/versions",
                "feature.window.b")
                .with(token("user-governance"))
                .header("Idempotency-Key", "window-b-cycle-attempt")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "scopeType":"SYSTEM",
                      "enabled":true,
                      "effectiveFrom":"2020-01-01T00:00:00Z",
                      "dependencies":["feature.window.a"],
                      "reason":"masked cycle attempt"
                    }
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code")
                .value("FEATURE_FLAG_DEPENDENCY_CYCLE"));
    }

    @Test
    void concurrentReciprocalEdgesCannotBothCommit() throws Exception {
        define("feature.concurrent.a", false);
        define("feature.concurrent.b", false);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<Integer> aToB = concurrentVersion(
            "feature.concurrent.a", "feature.concurrent.b", ready, start);
        CompletableFuture<Integer> bToA = concurrentVersion(
            "feature.concurrent.b", "feature.concurrent.a", ready, start);
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();

        int first = aToB.get(10, TimeUnit.SECONDS);
        int second = bToA.get(10, TimeUnit.SECONDS);
        assertEquals(1, (first == 200 ? 1 : 0) + (second == 200 ? 1 : 0));
        assertEquals(1, (first == 409 ? 1 : 0) + (second == 409 ? 1 : 0));
        assertEquals(1, jdbc.queryForObject("""
            SELECT count(*)
            FROM f07_feature_flag_dependencies relation
            JOIN f07_feature_flag_versions version
              ON version.id = relation.version_id
            JOIN f07_feature_flags flag ON flag.id = version.flag_id
            WHERE flag.flag_key IN (
                'feature.concurrent.a', 'feature.concurrent.b')
            """, Integer.class));
    }

    @Test
    void mutationRetriesReplayExactlyAndRejectChangedPayloads()
        throws Exception {
        String definition = """
            {
              "key":"feature.idempotent",
              "owner":"platform-operations",
              "defaultEnabled":false,
              "description":"Idempotent control-plane mutation",
              "reason":"verify lost response retry"
            }
            """;
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/api/v1/governance/feature-flags")
                    .with(token("user-governance"))
                    .header("Idempotency-Key", "define-idempotent")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(definition))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("feature.idempotent"));
        }
        assertEquals(1, jdbc.queryForObject("""
            SELECT count(*) FROM f07_feature_flags
            WHERE flag_key = 'feature.idempotent'
            """, Integer.class));
        assertEquals(1, jdbc.queryForObject("""
            SELECT count(*) FROM f07_feature_flag_transitions transition
            JOIN f07_feature_flags flag ON flag.id = transition.flag_id
            WHERE flag.flag_key = 'feature.idempotent'
            """, Integer.class));

        String firstVersion = """
            {
              "scopeType":"SYSTEM",
              "enabled":true,
              "effectiveFrom":"2020-01-01T00:00:00Z",
              "dependencies":[],
              "reason":"idempotent rollout"
            }
            """;
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post(
                    "/api/v1/governance/feature-flags/{key}/versions",
                    "feature.idempotent")
                    .with(token("user-governance"))
                    .header("Idempotency-Key", "version-idempotent")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(firstVersion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        }
        assertEquals(1, jdbc.queryForObject("""
            SELECT count(*) FROM f07_feature_flag_versions version
            JOIN f07_feature_flags flag ON flag.id = version.flag_id
            WHERE flag.flag_key = 'feature.idempotent'
            """, Integer.class));

        mvc.perform(post(
                "/api/v1/governance/feature-flags/{key}/versions",
                "feature.idempotent")
                .with(token("user-governance"))
                .header("Idempotency-Key", "version-idempotent")
                .contentType(MediaType.APPLICATION_JSON)
                .content(firstVersion.replace(
                    "\"enabled\":true", "\"enabled\":false")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void openApiMetricsAndReadinessExposeBoundedSafeContracts()
        throws Exception {
        mvc.perform(get("/v3/api-docs").with(token("user-governance")))
            .andExpect(status().isOk())
            .andExpect(jsonPath(
                "$.paths['/api/v1/governance/feature-flags']").exists())
            .andExpect(jsonPath(
                "$.paths['/api/v1/governance/feature-flags/{key}/versions']")
                .exists())
            .andExpect(jsonPath(
                "$.paths['/api/v1/governance/feature-flags/{key}/evaluation']")
                .exists());
        mvc.perform(get("/actuator/health/readiness"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));

        for (String name : new String[] {
            "vms.operational.jobs.pending",
            "vms.operational.outbox.pending",
            "vms.operational.dead.letter.count",
            "vms.operational.queue.oldest.age.seconds",
            "vms.operational.provider.freshness.age.seconds",
            "vms.operational.retention.action.required"
        }) {
            double value = meters.get(name).gauge().value();
            assertTrue(Double.isFinite(value) && value >= 0, name);
            assertTrue(meters.get(name).gauge().getId().getTags().isEmpty(),
                name + " must not expose identifiers");
        }
    }

    private void define(String key, boolean defaultEnabled) throws Exception {
        mvc.perform(post("/api/v1/governance/feature-flags")
                .with(token("user-governance"))
                .header("Idempotency-Key", "define-" + key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "key":"%s",
                      "owner":"platform-operations",
                      "defaultEnabled":%s,
                      "description":"Synthetic authoritative flag",
                      "reason":"test definition"
                    }
                    """.formatted(key, defaultEnabled)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.key").value(key));
    }

    private void version(
        String subject,
        String key,
        String body,
        int expectedVersion
    ) throws Exception {
        mvc.perform(post(
                "/api/v1/governance/feature-flags/{key}/versions", key)
                .with(token(subject))
                .header("Idempotency-Key",
                    "version-" + key + "-" + expectedVersion)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value(expectedVersion));
    }

    private CompletableFuture<Integer> concurrentVersion(
        String key,
        String dependency,
        CountDownLatch ready,
        CountDownLatch start
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return mvc.perform(post(
                        "/api/v1/governance/feature-flags/{key}/versions", key)
                        .with(token("user-governance"))
                        .header("Idempotency-Key",
                            "concurrent-" + key + "-" + dependency)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "scopeType":"SYSTEM",
                              "enabled":true,
                              "effectiveFrom":"2020-01-01T00:00:00Z",
                              "dependencies":["%s"],
                              "reason":"concurrent reciprocal edge"
                            }
                            """.formatted(dependency)))
                    .andReturn().getResponse().getStatus();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
    }
}
