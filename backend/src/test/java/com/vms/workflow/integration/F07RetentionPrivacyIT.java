package com.vms.workflow.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
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
    "spring.datasource.hikari.maximum-pool-size=4",
    "spring.flyway.locations=classpath:db/migration,classpath:db/testdata",
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:9/test-jwks",
    "vms.security.issuer=https://issuer.example.test",
    "vms.security.audience=vms-api",
    "vms.retention.two-person-release=true",
    "vms.retention.max-attempts=2",
    "vms.retention.retry-delay=PT1S"
})
@AutoConfigureMockMvc
class F07RetentionPrivacyIT {
    private static final UUID VENDOR =
        UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID CLIENT =
        UUID.fromString("00000000-0000-0000-0000-000000000102");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void grantSyntheticOrganizationGovernance() {
        grantOrgAdmin(
            "00000000-0000-0000-0000-000000000201", VENDOR,
            "22000000-0000-0000-0000-000000000101");
        grantOrgAdmin(
            "00000000-0000-0000-0000-000000000223", VENDOR,
            "22000000-0000-0000-0000-000000000102");
        grantOrgAdmin(
            "00000000-0000-0000-0000-000000000222", CLIENT,
            "22000000-0000-0000-0000-000000000103");
    }

    @Test
    void scheduleAndDryRunAreOrganizationScopedAndVersioned() throws Exception {
        assertEquals(0, jdbc.queryForObject("""
            SELECT count(*) FROM f07_retention_schedules
            WHERE organization_id = ?
              AND record_class = 'TEMPORARY_EXPORT_CAPABILITY'
            """, Integer.class, CLIENT));
        String schedule = mvc.perform(post(
                "/api/v1/governance/retention/schedules")
                .with(token("user-governance"))
                .header("Idempotency-Key", "retention-schedule-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "organizationId":"%s",
                      "recordClass":"TEMPORARY_EXPORT_CAPABILITY",
                      "retentionDays":7,
                      "policyReference":"synthetic-approved-policy",
                      "effectiveFrom":"2020-01-01T00:00:00Z"
                    }
                    """.formatted(CLIENT)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value(1))
            .andReturn().getResponse().getContentAsString();
        UUID scheduleId = UUID.fromString(
            mapper.readTree(schedule).path("scheduleId").asText());

        String run = mvc.perform(post(
                "/api/v1/governance/retention/runs/dry-run")
                .with(token("user-governance"))
                .header("Idempotency-Key", "retention-dry-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "organizationId":"%s",
                      "recordClass":"TEMPORARY_EXPORT_CAPABILITY",
                      "asOf":"2030-01-01T00:00:00Z"
                    }
                    """.formatted(CLIENT)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.organizationId").value(CLIENT.toString()))
            .andExpect(jsonPath("$.transitions[0].status")
                .value("DRY_RUN_COMPLETE"))
            .andReturn().getResponse().getContentAsString();
        UUID runId = UUID.fromString(
            mapper.readTree(run).path("runId").asText());

        mvc.perform(post(
                "/api/v1/governance/retention/runs/{id}/execute", runId)
                .with(token("user-governance"))
                .header("Idempotency-Key", "retention-execute-1"))
            .andExpect(status().isOk());
        assertEquals(0, jdbc.queryForObject("""
            SELECT count(*)
            FROM f07_retention_candidates candidate
            LEFT JOIN f07_retention_proofs proof
              ON proof.candidate_id = candidate.id
            WHERE candidate.run_id = ?
              AND proof.id IS NULL
              AND (
                  (candidate.target_type = 'REPORT_EXPORT' AND EXISTS (
                      SELECT 1 FROM f05_report_exports value
                      WHERE value.id = candidate.target_id
                        AND value.status = 'EXPIRED'
                  ))
                  OR
                  (candidate.target_type = 'PACKAGE_SHARE' AND EXISTS (
                      SELECT 1 FROM evidence_package_shares value
                      WHERE value.id = candidate.target_id
                        AND value.revoked_at IS NOT NULL
                  ))
              )
            """, Integer.class, runId));
        assertEquals(0, jdbc.queryForObject("""
            SELECT count(*) FROM f07_retention_candidates
            WHERE run_id = ? AND source_hash IS NULL
            """, Integer.class, runId));
        assertEquals(0, jdbc.queryForObject("""
            SELECT count(*) FROM f07_retention_proofs
            WHERE run_id = ? AND source_hash IS NULL
            """, Integer.class, runId));
        mvc.perform(get("/api/v1/governance/retention/runs/{id}", runId)
                .with(token("user-northstar")))
            .andExpect(status().isForbidden());
        assertThrows(DataAccessException.class, () -> jdbc.update("""
            UPDATE f07_retention_schedules
            SET retention_days = 8 WHERE id = ?
            """, scheduleId));
    }

    @Test
    void legalHoldRequiresDifferentReleaseApproverAndIsAppendOnly()
        throws Exception {
        UUID existingArtifactId = jdbc.query("""
            SELECT id FROM f05_private_artifacts
            WHERE owner_organization_id = ? AND NOT legal_hold
            ORDER BY recorded_at LIMIT 1
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, VENDOR);
        UUID artifactId = existingArtifactId == null
            ? insertPrivateArtifact() : existingArtifactId;
        String placed = mvc.perform(post(
                "/api/v1/governance/retention/artifacts/{id}/holds",
                artifactId)
                .with(token("user-arrow"))
                .header("Idempotency-Key", "hold-place-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reasonCode\":\"litigation_notice\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.twoPersonRelease").value(true))
            .andReturn().getResponse().getContentAsString();
        UUID holdId = UUID.fromString(
            mapper.readTree(placed).path("holdId").asText());

        assertThrows(DataAccessException.class, () -> jdbc.update("""
            UPDATE f05_private_artifacts
            SET legal_hold = FALSE
            WHERE id = ?
            """, artifactId));
        assertEquals(Boolean.TRUE, jdbc.queryForObject("""
            SELECT legal_hold FROM f05_private_artifacts WHERE id = ?
            """, Boolean.class, artifactId));

        mvc.perform(post(
                "/api/v1/finance/artifacts/{artifactId}/legal-hold",
                artifactId)
                .with(token("user-arrow"))
                .header("Idempotency-Key", "hold-legacy-bypass-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "enabled": false,
                      "reasonCode": "bypass_attempt"
                    }
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code")
                .value("F07_LEGAL_HOLD_RELEASE_WORKFLOW_REQUIRED"));
        assertEquals(Boolean.TRUE, jdbc.queryForObject("""
            SELECT legal_hold FROM f05_private_artifacts WHERE id = ?
            """, Boolean.class, artifactId));

        mvc.perform(post(
                "/api/v1/governance/retention/artifacts/{artifact}/holds/{hold}/release-approval",
                artifactId, holdId)
                .with(token("user-sod"))
                .header("Idempotency-Key", "hold-release-no-request-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reasonCode\":\"matter_closed\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code")
                .value("LEGAL_HOLD_RELEASE_REQUEST_REQUIRED"));
        mvc.perform(post(
                "/api/v1/governance/retention/artifacts/{artifact}/holds/{hold}/release",
                artifactId, holdId)
                .with(token("user-arrow"))
                .header("Idempotency-Key", "hold-release-request-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reasonCode\":\"matter_closed\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.transitions[1].action")
                .value("RELEASE_REQUESTED"));
        mvc.perform(post(
                "/api/v1/governance/retention/artifacts/{artifact}/holds/{hold}/release-approval",
                artifactId, holdId)
                .with(token("user-arrow"))
                .header("Idempotency-Key", "hold-release-self-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reasonCode\":\"matter_closed\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(post(
                "/api/v1/governance/retention/artifacts/{artifact}/holds/{hold}/release-approval",
                artifactId, holdId)
                .with(token("user-sod"))
                .header("Idempotency-Key", "hold-release-second-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reasonCode\":\"matter_closed\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.transitions[2].action")
                .value("RELEASE_APPROVED"));

        assertEquals(Boolean.FALSE, jdbc.queryForObject("""
            SELECT legal_hold FROM f05_private_artifacts WHERE id = ?
            """, Boolean.class, artifactId));
        assertThrows(DataAccessException.class, () -> jdbc.update("""
            DELETE FROM f07_legal_hold_transitions WHERE hold_id = ?
            """, holdId));
    }

    @Test
    void singlePersonHoldReleaseAuditsTheEffectiveRelease()
        throws Exception {
        UUID artifactId = insertPrivateArtifact();
        UUID holdId = UUID.randomUUID();
        jdbc.execute("""
            DO $setup$
            DECLARE
                hold_id UUID := '%s'::uuid;
                artifact_id UUID := '%s'::uuid;
                correlation UUID := gen_random_uuid();
            BEGIN
                INSERT INTO f07_legal_holds(
                    id, artifact_id, organization_id, reason_code,
                    two_person_release, placed_by_subject,
                    authority_snapshot, correlation_id
                ) VALUES (
                    hold_id, artifact_id, '%s'::uuid, 'POLICY_HOLD', FALSE,
                    'user-arrow', '{}'::jsonb, correlation
                );
                INSERT INTO f07_legal_hold_transitions(
                    id, hold_id, action, prior_hold, effective_hold,
                    reason_code, actor_subject, authority_snapshot,
                    correlation_id
                ) VALUES (
                    gen_random_uuid(), hold_id, 'PLACED', FALSE, TRUE,
                    'POLICY_HOLD', 'user-arrow', '{}'::jsonb, correlation
                );
                INSERT INTO f05_artifact_hold_transitions(
                    id, artifact_id, prior_legal_hold, legal_hold,
                    reason_code, authority_snapshot, actor_subject,
                    correlation_id
                ) VALUES (
                    gen_random_uuid(), artifact_id, FALSE, TRUE,
                    'POLICY_HOLD', '{}'::jsonb, 'user-arrow', correlation
                );
                UPDATE f05_private_artifacts
                SET legal_hold = TRUE
                WHERE id = artifact_id AND legal_hold = FALSE;
            END
            $setup$
            """.formatted(holdId, artifactId, VENDOR));

        mvc.perform(post(
                "/api/v1/governance/retention/artifacts/{artifact}/holds/{hold}/release",
                artifactId, holdId)
                .with(token("user-arrow"))
                .header("Idempotency-Key", "single-person-release")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reasonCode\":\"matter_closed\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.transitions[1].action")
                .value("RELEASE_APPROVED"));

        assertEquals(1, jdbc.queryForObject("""
            SELECT count(*) FROM f05_audit_events
            WHERE object_id = ?
              AND action = 'F07_LEGAL_HOLD_RELEASED'
            """, Integer.class, artifactId));
        assertEquals(0, jdbc.queryForObject("""
            SELECT count(*) FROM f05_audit_events
            WHERE object_id = ?
              AND action = 'F07_LEGAL_HOLD_RELEASE_REQUESTED'
            """, Integer.class, artifactId));
    }

    @Test
    void classificationAndDatabaseBoundaryRejectCommercialFields()
        throws Exception {
        mvc.perform(get("/api/v1/governance/retention/classification")
                .queryParam("organizationId", CLIENT.toString())
                .with(token("user-governance")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.assetName == 'salary')]").exists())
            .andExpect(jsonPath("$[?(@.assetName == 'rate')]").exists())
            .andExpect(jsonPath("$[?(@.assetName == 'markup')]").exists());

        assertThrows(DataAccessException.class, () -> jdbc.queryForObject("""
            SELECT f07_assert_no_commercial_fields(
                '{"filters":{"salary":100}}'::jsonb)
            """, Boolean.class));
        for (String bypass : new String[]{
            "{\"nested\":{\"salary_amount\":100}}",
            "{\"salaryBand\":\"A\"}",
            "{\"markup_percent\":10}",
            "{\"commercialRate\":200}"
        }) {
            assertThrows(DataAccessException.class, () -> jdbc.queryForObject("""
                SELECT f07_assert_no_commercial_fields(?::jsonb)
                """, Boolean.class, bypass));
        }
        assertEquals(Boolean.TRUE, jdbc.queryForObject("""
            SELECT f07_assert_no_commercial_fields(
                '{"filters":{"status":"READY"}}'::jsonb)
            """, Boolean.class));
    }

    @Test
    void committedHoldLinearizesBeforeRetentionCapabilityExpiry()
        throws Exception {
        UUID artifactId = insertPrivateArtifact();
        UUID exportId = insertReadyExport();
        jdbc.update("""
            UPDATE f05_report_exports
            SET result_artifact_id = ?
            WHERE id = ?
            """, artifactId, exportId);
        UUID runId = insertDryRun(exportId);
        UUID holdId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();

        try (Connection holdConnection = dataSource.getConnection()) {
            holdConnection.setAutoCommit(false);
            try {
                execute(holdConnection, """
                    INSERT INTO f07_legal_holds(
                        id, artifact_id, organization_id, reason_code,
                        two_person_release, placed_by_subject,
                        authority_snapshot, correlation_id
                    ) VALUES (?, ?, ?, 'LITIGATION_HOLD', TRUE,
                              'user-arrow', '{}'::jsonb, ?)
                    """, holdId, artifactId, VENDOR, correlationId);
                execute(holdConnection, """
                    INSERT INTO f07_legal_hold_transitions(
                        id, hold_id, action, prior_hold, effective_hold,
                        reason_code, actor_subject, authority_snapshot,
                        correlation_id
                    ) VALUES (?, ?, 'PLACED', FALSE, TRUE,
                              'LITIGATION_HOLD', 'user-arrow', '{}'::jsonb, ?)
                    """, UUID.randomUUID(), holdId, correlationId);
                execute(holdConnection, """
                    INSERT INTO f05_artifact_hold_transitions(
                        id, artifact_id, prior_legal_hold, legal_hold,
                        reason_code, authority_snapshot, actor_subject,
                        correlation_id
                    ) VALUES (?, ?, FALSE, TRUE, 'LITIGATION_HOLD',
                              '{}'::jsonb, 'user-arrow', ?)
                    """, UUID.randomUUID(), artifactId, correlationId);
                execute(holdConnection, """
                    UPDATE f05_private_artifacts
                    SET legal_hold = TRUE
                    WHERE id = ? AND legal_hold = FALSE
                    """, artifactId);

                CompletableFuture<Integer> expiry =
                    CompletableFuture.supplyAsync(() -> {
                        try {
                            return mvc.perform(post(
                                    "/api/v1/governance/retention/runs/{id}/execute",
                                    runId)
                                    .with(token("user-arrow"))
                                    .header(
                                        "Idempotency-Key",
                                        "hold-before-expiry-" + runId))
                                .andReturn().getResponse().getStatus();
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                    });
                assertThrows(
                    java.util.concurrent.TimeoutException.class,
                    () -> expiry.get(250, TimeUnit.MILLISECONDS));
                holdConnection.commit();
                assertEquals(200, expiry.get(30, TimeUnit.SECONDS));
            } catch (Exception failure) {
                holdConnection.rollback();
                throw failure;
            } catch (AssertionError failure) {
                holdConnection.rollback();
                throw failure;
            }
        }

        assertEquals("READY", jdbc.queryForObject("""
            SELECT status FROM f05_report_exports WHERE id = ?
            """, String.class, exportId));
        assertEquals(1, jdbc.queryForObject("""
            SELECT count(*)
            FROM f07_retention_execution_results result
            JOIN f07_retention_candidates candidate
              ON candidate.id = result.candidate_id
            WHERE candidate.run_id = ?
              AND result.outcome = 'SKIPPED_HELD'
            """, Integer.class, runId));
        assertEquals(0, jdbc.queryForObject("""
            SELECT count(*) FROM f07_retention_proofs
            WHERE run_id = ?
            """, Integer.class, runId));
    }

    @Test
    void executionIsSingleOwnerAndDeadLetterRecoveryStartsNewBoundedCycle()
        throws Exception {
        UUID concurrentExport = insertReadyExport();
        UUID concurrentRun = insertDryRun(concurrentExport);
        jdbc.execute("""
            CREATE OR REPLACE FUNCTION f07_test_slow_export()
            RETURNS TRIGGER LANGUAGE plpgsql
            SET search_path = pg_catalog, public AS $$
            BEGIN
                IF NEW.id = '%s'::uuid THEN
                    PERFORM pg_sleep(1.5);
                END IF;
                RETURN NEW;
            END
            $$
            """.formatted(concurrentExport));
        jdbc.execute("""
            CREATE TRIGGER f07_test_slow_export
            BEFORE UPDATE ON f05_report_exports
            FOR EACH ROW EXECUTE FUNCTION f07_test_slow_export()
            """);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            CompletableFuture<Integer> first = concurrentExecution(
                concurrentRun, "retention-concurrent-a", ready, start, 0);
            CompletableFuture<Integer> second = concurrentExecution(
                concurrentRun, "retention-concurrent-b", ready, start, 1_100);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            int firstStatus = first.get(10, TimeUnit.SECONDS);
            int secondStatus = second.get(10, TimeUnit.SECONDS);
            assertEquals(1, (firstStatus == 200 ? 1 : 0)
                + (secondStatus == 200 ? 1 : 0));
            assertEquals(1, (firstStatus == 409 ? 1 : 0)
                + (secondStatus == 409 ? 1 : 0));
            assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM f07_retention_run_transitions
                WHERE run_id = ? AND status = 'EXECUTION_STARTED'
                """, Integer.class, concurrentRun));
            assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM f07_retention_proofs WHERE run_id = ?
                """, Integer.class, concurrentRun));
        } finally {
            jdbc.execute(
                "DROP TRIGGER IF EXISTS f07_test_slow_export "
                + "ON f05_report_exports");
            jdbc.execute("DROP FUNCTION IF EXISTS f07_test_slow_export()");
        }

        UUID failedExport = insertReadyExport();
        UUID failedRun = insertDryRun(failedExport);
        jdbc.execute("""
            CREATE OR REPLACE FUNCTION f07_test_fail_export()
            RETURNS TRIGGER LANGUAGE plpgsql
            SET search_path = pg_catalog, public AS $$
            BEGIN
                IF NEW.id = '%s'::uuid THEN
                    RAISE EXCEPTION 'injected retryable export failure';
                END IF;
                RETURN NEW;
            END
            $$
            """.formatted(failedExport));
        jdbc.execute("""
            CREATE TRIGGER f07_test_fail_export
            BEFORE UPDATE ON f05_report_exports
            FOR EACH ROW EXECUTE FUNCTION f07_test_fail_export()
            """);
        try {
            mvc.perform(post(
                    "/api/v1/governance/retention/runs/{id}/execute",
                    failedRun)
                    .with(token("user-arrow"))
                    .header("Idempotency-Key", "retention-failure-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                    "$.transitions[?(@.status == 'RETRY_SCHEDULED')]")
                    .exists());
            mvc.perform(post(
                    "/api/v1/governance/retention/runs/{id}/execute",
                    failedRun)
                    .with(token("user-arrow"))
                    .header("Idempotency-Key", "retention-failure-early"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RETENTION_RETRY_NOT_DUE"));
            TimeUnit.MILLISECONDS.sleep(1_100);
            mvc.perform(post(
                    "/api/v1/governance/retention/runs/{id}/execute",
                    failedRun)
                    .with(token("user-arrow"))
                    .header("Idempotency-Key", "retention-failure-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                    "$.transitions[?(@.status == 'DEAD_LETTER')]")
                    .exists());
            mvc.perform(post(
                    "/api/v1/governance/retention/runs/{id}/execute",
                    failedRun)
                    .with(token("user-arrow"))
                    .header("Idempotency-Key", "retention-failure-3"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                    .value("RETENTION_RUN_DEAD_LETTERED"));
            mvc.perform(post(
                    "/api/v1/governance/retention/runs/{id}/dead-letter-recovery",
                    failedRun)
                    .with(token("user-arrow"))
                    .header("Idempotency-Key", "retention-recovery-1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reasonCode\":\"operator_verified_recovery\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                    "$.transitions[?(@.status == 'RECOVERY_AUTHORIZED')]")
                    .exists());
        } finally {
            jdbc.execute(
                "DROP TRIGGER IF EXISTS f07_test_fail_export "
                + "ON f05_report_exports");
            jdbc.execute("DROP FUNCTION IF EXISTS f07_test_fail_export()");
        }
        mvc.perform(post(
                "/api/v1/governance/retention/runs/{id}/execute", failedRun)
                .with(token("user-arrow"))
                .header("Idempotency-Key", "retention-recovered-execute"))
            .andExpect(status().isOk())
            .andExpect(jsonPath(
                "$.transitions[?(@.status == 'COMPLETED')]").exists());
        assertEquals(3, jdbc.queryForObject("""
            SELECT count(*) FROM f07_retention_run_transitions
            WHERE run_id = ? AND status = 'EXECUTION_STARTED'
            """, Integer.class, failedRun));
        assertEquals(1, jdbc.queryForObject("""
            SELECT count(*) FROM f07_retention_proofs WHERE run_id = ?
            """, Integer.class, failedRun));
    }

    @Test
    void expiredExecutionLeaseTakeoverUsesMonotonicTransitionOrder()
        throws Exception {
        UUID exportId = insertReadyExport();
        UUID runId = insertDryRun(exportId);
        UUID abandonedOwner = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO f07_retention_run_transitions(
                id, run_id, status, attempt, eligible_count, skipped_count,
                failure_count, reason_code, actor_subject, correlation_id
            ) VALUES (?, ?, 'EXECUTION_STARTED', 1, 0, 0, 0,
                      'ABANDONED_TEST_OWNER', 'user-arrow', ?)
            """, UUID.randomUUID(), runId, UUID.randomUUID());
        jdbc.update("""
            INSERT INTO f07_retention_execution_leases(
                run_id, owner_id, attempt, acquired_at,
                heartbeat_at, lease_expires_at
            ) VALUES (?, ?, 1,
                      CURRENT_TIMESTAMP - INTERVAL '2 hours',
                      CURRENT_TIMESTAMP - INTERVAL '2 hours',
                      CURRENT_TIMESTAMP - INTERVAL '1 hour')
            """, runId, abandonedOwner);

        mvc.perform(post(
                "/api/v1/governance/retention/runs/{id}/execute", runId)
                .with(token("user-arrow"))
                .header("Idempotency-Key", "retention-expired-owner-takeover"))
            .andExpect(status().isOk())
            .andExpect(jsonPath(
                "$.transitions[?(@.status == 'COMPLETED')]").exists());

        assertEquals(
            List.of(
                "DRY_RUN_COMPLETE", "EXECUTION_STARTED", "RETRY_SCHEDULED",
                "EXECUTION_STARTED", "COMPLETED"),
            jdbc.queryForList("""
                SELECT status
                FROM f07_retention_run_transitions
                WHERE run_id = ?
                ORDER BY transition_sequence
                """, String.class, runId));
        assertEquals(0, jdbc.queryForObject("""
            SELECT count(*) FROM f07_retention_execution_leases
            WHERE run_id = ?
            """, Integer.class, runId));
    }

    @Test
    void openApiPublishesRetentionHoldAndClassificationContracts()
        throws Exception {
        mvc.perform(get("/v3/api-docs").with(token("user-arrow")))
            .andExpect(status().isOk())
            .andExpect(jsonPath(
                "$.paths['/api/v1/governance/retention/schedules']").exists())
            .andExpect(jsonPath(
                "$.paths['/api/v1/governance/retention/runs/dry-run']").exists())
            .andExpect(jsonPath(
                "$.paths['/api/v1/governance/retention/runs/{runId}/execute']")
                .exists())
            .andExpect(jsonPath(
                "$.paths['/api/v1/governance/retention/runs/{runId}/dead-letter-recovery']")
                .exists())
            .andExpect(jsonPath(
                "$.paths['/api/v1/governance/retention/artifacts/{artifactId}/holds']")
                .exists())
            .andExpect(jsonPath(
                "$.paths['/api/v1/governance/retention/classification']")
                .exists());
    }

    private void grantOrgAdmin(
        String userId, UUID organizationId, String assignmentId
    ) {
        jdbc.update("""
            INSERT INTO role_assignments(
                id, user_profile_id, organization_id, role_id,
                scope_type, scope_id, status, valid_from
            ) VALUES (?::uuid, ?::uuid, ?,
                      '11000000-0000-0000-0000-000000000001',
                      'ORGANIZATION', ?, 'ACTIVE', DATE '2020-01-01')
            ON CONFLICT DO NOTHING
            """, assignmentId, userId, organizationId, organizationId);
    }

    private void execute(
        Connection connection,
        String sql,
        Object... values
    ) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            statement.executeUpdate();
        }
    }

    private CompletableFuture<Integer> concurrentExecution(
        UUID runId,
        String idempotencyKey,
        CountDownLatch ready,
        CountDownLatch start,
        long delayMillis
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                if (delayMillis > 0) {
                    TimeUnit.MILLISECONDS.sleep(delayMillis);
                }
                return mvc.perform(post(
                        "/api/v1/governance/retention/runs/{id}/execute", runId)
                        .with(token("user-arrow"))
                        .header("Idempotency-Key", idempotencyKey))
                    .andReturn().getResponse().getStatus();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    private UUID insertReadyExport() {
        UUID exportId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO f05_report_exports(
                id, organization_id, report_code, report_version, format,
                filters, status, progress, result_hash,
                requested_by_subject, authority_snapshot, requested_at,
                completed_at, expires_at, correlation_id
            ) VALUES (?, ?, 'F07_TEST', '1', 'JSON', '{}'::jsonb,
                      'READY', 100, repeat('a', 64), 'user-arrow',
                      '{}'::jsonb, CURRENT_TIMESTAMP - INTERVAL '30 days',
                      CURRENT_TIMESTAMP - INTERVAL '30 days',
                      CURRENT_TIMESTAMP - INTERVAL '20 days', ?)
            """, exportId, VENDOR, UUID.randomUUID());
        return exportId;
    }

    private UUID insertPrivateArtifact() {
        UUID artifactId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO f05_private_artifacts(
                id, engagement_month_id, owner_organization_id,
                logical_type, safe_name, media_type, byte_size, content_hash,
                object_key, object_version, classification, retention_class,
                scan_status, scan_engine, scanned_at, provider_status, source,
                uploaded_by_subject, correlation_id
            ) VALUES (
                ?, '00000000-0000-0000-0000-000000000602', ?,
                'F07_LEGAL_HOLD_TEST', 'f07-hold-test.txt', 'text/plain',
                4, replace(?::text, '-', '') || replace(?::text, '-', ''),
                ?, '1', 'CONFIDENTIAL',
                'GOVERNANCE_EVIDENCE', 'PASSED', 'synthetic-test',
                CURRENT_TIMESTAMP, 'LOCAL_METADATA_ONLY', 'SYNTHETIC_TEST',
                'user-arrow', ?
            )
            """, artifactId, VENDOR, artifactId, artifactId,
            "f07/legal-hold/" + artifactId, UUID.randomUUID());
        return artifactId;
    }

    private UUID insertDryRun(UUID exportId) {
        UUID scheduleId = UUID.randomUUID();
        Integer version = jdbc.queryForObject("""
            SELECT COALESCE(MAX(version), 0) + 1
            FROM f07_retention_schedules
            WHERE organization_id = ?
              AND record_class = 'TEMPORARY_EXPORT_CAPABILITY'
            """, Integer.class, VENDOR);
        jdbc.update("""
            INSERT INTO f07_retention_schedules(
                id, organization_id, record_class, version, retention_days,
                policy_reference, effective_from, created_by_subject,
                authority_snapshot, correlation_id
            ) VALUES (?, ?, 'TEMPORARY_EXPORT_CAPABILITY', ?, 7,
                      'f07-lifecycle-test', CURRENT_TIMESTAMP - INTERVAL '1 day',
                      'user-arrow', '{}'::jsonb, ?)
            """, scheduleId, VENDOR, version, UUID.randomUUID());
        UUID runId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO f07_retention_runs(
                id, schedule_id, organization_id, record_class, as_of,
                requested_by_subject, authority_snapshot, correlation_id
            ) VALUES (?, ?, ?, 'TEMPORARY_EXPORT_CAPABILITY',
                      CURRENT_TIMESTAMP, 'user-arrow', '{}'::jsonb, ?)
            """, runId, scheduleId, VENDOR, UUID.randomUUID());
        jdbc.update("""
            INSERT INTO f07_retention_candidates(
                id, run_id, target_type, target_id, artifact_id, deadline,
                decision, reason_code, classification, source_hash,
                evidence_preserved
            ) VALUES (?, ?, 'REPORT_EXPORT', ?,
                      (SELECT result_artifact_id
                       FROM f05_report_exports WHERE id = ?),
                      CURRENT_TIMESTAMP - INTERVAL '1 day', 'ELIGIBLE',
                      'CAPABILITY_EXPIRY_DUE_EVIDENCE_PRESERVED',
                      'CONFIDENTIAL', repeat('a', 64), TRUE)
            """, UUID.randomUUID(), runId, exportId, exportId);
        jdbc.update("""
            INSERT INTO f07_retention_run_transitions(
                id, run_id, status, attempt, eligible_count, skipped_count,
                failure_count, reason_code, actor_subject, correlation_id
            ) VALUES (?, ?, 'DRY_RUN_COMPLETE', 0, 1, 0, 0,
                      'DRY_RUN_RECORDED', 'user-arrow', ?)
            """, UUID.randomUUID(), runId, UUID.randomUUID());
        return runId;
    }
}
