package com.vms.workflow.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.sql.Savepoint;
import java.time.OffsetDateTime;
import java.util.List;

import static com.vms.workflow.integration.F04TestSupport.MONTH;
import static com.vms.workflow.integration.F04TestSupport.PROJECT_A;
import static com.vms.workflow.integration.F04TestSupport.token;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:tc:postgresql:18-alpine:///vms_workflow",
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
class F04RegressionIT {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void f04VerticalNeverMutatesFrozenF03PlanBaselineOrCommitment()
        throws Exception {
        F04TestSupport.FrozenBaseline baseline =
            F04TestSupport.frozenBaseline(mvc, mapper);
        String planChecksum = jdbc.queryForObject("""
            SELECT checksum FROM delivery_plan_versions WHERE id = ?
            """, String.class, baseline.planVersionId());
        int commitmentCount = count("""
            SELECT COUNT(*) FROM commitment_outbox WHERE plan_version_id = ?
            """, baseline.planVersionId());

        F04TestSupport.CompletedCertification completed =
            completeExistingBaseline(baseline);
        assertEquals(planChecksum, jdbc.queryForObject("""
            SELECT checksum FROM delivery_plan_versions WHERE id = ?
            """, String.class, baseline.planVersionId()));
        assertEquals(baseline.checksum(), jdbc.queryForObject("""
            SELECT checksum FROM delivery_plan_baselines WHERE id = ?
            """, String.class, baseline.baselineId()));
        assertEquals(commitmentCount, count("""
            SELECT COUNT(*) FROM commitment_outbox WHERE plan_version_id = ?
            """, baseline.planVersionId()));
        assertSqlRejected("""
            UPDATE delivery_plan_baselines SET checksum = repeat('0',64)
            WHERE id = '%s'::uuid
            """.formatted(baseline.baselineId()));
        assertEquals(completed.baseline().baselineId(), baseline.baselineId());
    }

    @Test
    void f04ReadinessReferencesButNeverMutatesClosedF02Snapshot()
        throws Exception {
        F04TestSupport.CompletedCertification completed =
            F04TestSupport.completedCertification(mvc, mapper, jdbc);
        String checksum = jdbc.queryForObject("""
            SELECT checksum FROM attendance_snapshot_versions WHERE id = ?
            """, String.class, completed.attendanceSnapshotId());
        assertSqlRejected("""
            UPDATE attendance_snapshot_versions SET checksum = repeat('0',64)
            WHERE id = '%s'::uuid
            """.formatted(completed.attendanceSnapshotId()));
        assertSqlRejected("""
            DELETE FROM attendance_snapshot_versions
            WHERE id = '%s'::uuid
            """.formatted(completed.attendanceSnapshotId()));
        assertEquals(checksum, jdbc.queryForObject("""
            SELECT checksum FROM attendance_snapshot_versions WHERE id = ?
            """, String.class, completed.attendanceSnapshotId()));
    }

    @Test
    void f04DoesNotCreateF05InvoicePackageOrProcurementFacts()
        throws Exception {
        int importedLegacyInvoices = count("SELECT COUNT(*) FROM legacy_invoices");
        F04TestSupport.CompletedCertification completed =
            F04TestSupport.completedCertification(mvc, mapper, jdbc);
        String planChecksum = jdbc.queryForObject("""
            SELECT checksum
            FROM delivery_plan_versions
            WHERE id = ?
            """, String.class, completed.baseline().planVersionId());
        String baselineChecksum = jdbc.queryForObject("""
            SELECT checksum
            FROM delivery_plan_baselines
            WHERE id = ?
            """, String.class, completed.baseline().baselineId());
        int commitmentCount = count("""
            SELECT COUNT(*)
            FROM commitment_outbox
            WHERE plan_version_id = ?
            """, completed.baseline().planVersionId());
        OffsetDateTime now = OffsetDateTime.now();
        F04TestSupport.DirectConfirmation request =
            F04TestSupport.directConfirmation(
                jdbc, completed, "ANY_ONE", 1, now.minusMinutes(1),
                now.plusDays(2),
                List.of(new F04TestSupport.EligibleFixture(
                    "user-reliance", "ravi@reliance.example", PROJECT_A)));

        mvc.perform(post(
                    "/api/v1/certification/confirmation-requests/{requestId}/actions",
                    request.requestId())
                .with(token("user-reliance"))
                .header("If-Match", "1")
                .header("Idempotency-Key", "regression-confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"expectedRequestVersion":1,"decision":"CONFIRM"}
                    """))
            .andExpect(status().isOk());

        assertEquals("CONFIRMED", jdbc.queryForObject("""
            SELECT status
            FROM business_confirmation_requests
            WHERE id = ?
            """, String.class, request.requestId()));
        assertEquals(1, count("""
            SELECT COUNT(*)
            FROM f05_certification_handoffs
            WHERE confirmation_request_id = ?
              AND status = 'READY_LOCAL'
            """, request.requestId()));
        assertEquals(1, count("""
            SELECT COUNT(*)
            FROM f05_handoff_publish_jobs job
            JOIN f05_certification_handoffs handoff
              ON handoff.id = job.handoff_id
            WHERE handoff.confirmation_request_id = ?
              AND job.status = 'PENDING'
            """, request.requestId()));
        assertEquals(planChecksum, jdbc.queryForObject("""
            SELECT checksum
            FROM delivery_plan_versions
            WHERE id = ?
            """, String.class, completed.baseline().planVersionId()));
        assertEquals(baselineChecksum, jdbc.queryForObject("""
            SELECT checksum
            FROM delivery_plan_baselines
            WHERE id = ?
            """, String.class, completed.baseline().baselineId()));
        assertEquals(commitmentCount, count("""
            SELECT COUNT(*)
            FROM commitment_outbox
            WHERE plan_version_id = ?
            """, completed.baseline().planVersionId()));
        assertEquals(importedLegacyInvoices,
            count("SELECT COUNT(*) FROM legacy_invoices"));
        assertEquals(0, count("""
            SELECT COUNT(*) FROM information_schema.tables
            WHERE table_schema = 'public'
              AND table_name IN (
                'invoices','invoice_versions','procurement_reviews',
                'evidence_packages','evidence_package_versions')
            """));
    }

    private F04TestSupport.CompletedCertification completeExistingBaseline(
        F04TestSupport.FrozenBaseline baseline
    ) throws Exception {
        var initial = F04TestSupport.workspace(mvc, mapper, "user-arrow");
        var draft = F04TestSupport.saveCompleteDraft(
            mvc, mapper, baseline, "user-arrow", initial.path("version").asLong(),
            "regression-save");
        var submissionId = java.util.UUID.fromString(
            draft.path("submission").path("id").asText());
        F04TestSupport.submit(
            mvc, mapper, submissionId, 1, "regression-submit");
        var certified = F04TestSupport.certifyAccepted(
            mvc, mapper, baseline, submissionId, "user-reliance",
            "regression-certify");
        var summarized = F04TestSupport.summary(
            mvc, mapper, certified.path("version").asLong(), "user-reliance",
            "regression-summary");
        java.util.UUID attendanceId = java.util.UUID.randomUUID();
        jdbc.update("""
            INSERT INTO attendance_snapshot_versions
                (id, engagement_month_id, version, checksum, day_count,
                 closed_by_subject)
            VALUES (?, ?::uuid, 1, repeat('a',64), 0, 'test')
            """, attendanceId, MONTH);
        return new F04TestSupport.CompletedCertification(
            baseline, submissionId,
            java.util.UUID.fromString(
                summarized.path("summary").path("id").asText()),
            attendanceId, summarized.path("version").asLong());
    }

    private void assertSqlRejected(String sql) {
        Boolean rejected = jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            Savepoint savepoint = connection.setSavepoint();
            try (var statement = connection.createStatement()) {
                statement.executeUpdate(sql);
                connection.rollback(savepoint);
                return false;
            } catch (java.sql.SQLException expected) {
                connection.rollback(savepoint);
                return true;
            } finally {
                connection.releaseSavepoint(savepoint);
            }
        });
        assertTrue(Boolean.TRUE.equals(rejected), "SQL should be rejected: " + sql);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}
