package com.vms.workflow.integration;

import com.vms.workflow.application.CanonicalEvidenceHasher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.Savepoint;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.vms.workflow.integration.F04TestSupport.MONTH;
import static com.vms.workflow.integration.F04TestSupport.PROJECT_A;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
class CertificationPersistenceIT {
    private static final String NORTHSTAR_MONTH =
        "00000000-0000-0000-0000-000000000603";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private CanonicalEvidenceHasher hasher;

    @Test
    void emptyDatabaseMigrationsExposeF04SchemaWithoutCommercialData()
        throws Exception {
        assertEquals("1004", jdbc.queryForObject("""
            SELECT version FROM flyway_schema_history
            WHERE success ORDER BY installed_rank DESC LIMIT 1
            """, String.class));
        assertTrue(count("""
            SELECT COUNT(*) FROM information_schema.tables
            WHERE table_schema = 'public'
              AND table_name IN (
                'delivery_submissions','deliverable_certifications',
                'monthly_certification_summaries',
                'business_confirmation_requests','notification_outbox',
                'certification_readiness_runs')
            """) >= 6);
        assertEquals(0, count("""
            SELECT COUNT(*) FROM information_schema.columns
            WHERE table_schema = 'public'
              AND table_name IN (
                'delivery_submissions','deliverable_certifications',
                'monthly_certification_summaries',
                'business_confirmation_requests','notification_outbox')
              AND column_name ~ '(salary|payroll|hourly_rate|markup|invoice_amount)'
            """));
    }

    @Test
    void submissionRejectsCrossMonthPlanAndBaselineAtDatabaseBoundary()
        throws Exception {
        F04TestSupport.FrozenBaseline baseline =
            F04TestSupport.frozenBaseline(mvc, mapper);
        // This method is replaced by the HTTP-backed class tests. The direct DB
        // assertion here covers the same trigger from a future/bypassing writer.
        UUID policy = ensurePolicy(baseline);
        assertSqlRejected("""
            INSERT INTO delivery_submissions
                (id, engagement_month_id, plan_version_id, baseline_id,
                 policy_version_id, version, status, summary,
                 vendor_declaration_accepted, declaration_text,
                 created_by_subject)
            VALUES (gen_random_uuid(), '%s'::uuid, '%s'::uuid, '%s'::uuid,
                    '%s'::uuid, 1, 'DRAFT', 'cross scope', true,
                    'declaration', 'test')
            """.formatted(
                NORTHSTAR_MONTH, baseline.planVersionId(), baseline.baselineId(),
                policy));
    }

    @Test
    void summaryAndConfirmationRejectCrossMonthAndCrossTenantSources()
        throws Exception {
        F04TestSupport.CompletedCertification completed = completed();
        UUID policyId = jdbc.queryForObject("""
            SELECT policy_version_id FROM delivery_submissions WHERE id = ?
            """, UUID.class, completed.submissionId());
        UUID roundId = jdbc.queryForObject("""
            SELECT id FROM certification_rounds WHERE submission_id = ?
            ORDER BY round_number DESC LIMIT 1
            """, UUID.class, completed.submissionId());
        UUID foreignPredecessor = UUID.randomUUID();
        jdbc.execute("""
            ALTER TABLE monthly_certification_summaries
            DISABLE TRIGGER f04_summary_scope_gate
            """);
        try {
            jdbc.update("""
                INSERT INTO monthly_certification_summaries
                    (id, engagement_month_id, submission_id, round_id,
                     plan_version_id, baseline_id, policy_version_id, version,
                     status, monthly_decision, observations, risks, manifest,
                     checksum, authority_snapshot, created_by_subject)
                SELECT ?, ?::uuid, submission_id, round_id, plan_version_id,
                       baseline_id, policy_version_id, 99, 'SUPERSEDED',
                       monthly_decision, observations, risks, manifest,
                       checksum, authority_snapshot, 'legacy-test-setup'
                FROM monthly_certification_summaries
                WHERE id = ?
                """, foreignPredecessor, NORTHSTAR_MONTH,
                completed.summaryId());
        } finally {
            jdbc.execute("""
                ALTER TABLE monthly_certification_summaries
                ENABLE TRIGGER f04_summary_scope_gate
                """);
        }

        assertSqlRejected("""
            INSERT INTO monthly_certification_summaries
                (id, engagement_month_id, submission_id, round_id,
                 plan_version_id, baseline_id, policy_version_id, version,
                 status, supersedes_id, monthly_decision, manifest, checksum,
                 authority_snapshot, created_by_subject)
            VALUES (gen_random_uuid(), '%s'::uuid, '%s'::uuid, '%s'::uuid,
                    '%s'::uuid, '%s'::uuid, '%s'::uuid, 2, 'SUPERSEDED',
                    '%s'::uuid, 'CERTIFIED', '{}'::jsonb, repeat('c', 64),
                    '{}'::jsonb, 'test')
            """.formatted(
                MONTH, completed.submissionId(), roundId,
                completed.baseline().planVersionId(),
                completed.baseline().baselineId(), policyId,
                foreignPredecessor));

        assertAllSqlRejected(
            """
                INSERT INTO monthly_certification_summaries
                    (id, engagement_month_id, submission_id, round_id,
                     plan_version_id, baseline_id, policy_version_id, version,
                     status, monthly_decision, manifest, checksum,
                     authority_snapshot, created_by_subject)
                VALUES (gen_random_uuid(), '%s'::uuid, '%s'::uuid, '%s'::uuid,
                        '%s'::uuid, '%s'::uuid, '%s'::uuid, 1, 'CURRENT',
                        'CERTIFIED', '{}'::jsonb, repeat('c', 64),
                        '{}'::jsonb, 'test')
                """.formatted(
                    NORTHSTAR_MONTH, completed.submissionId(), roundId,
                    completed.baseline().planVersionId(),
                    completed.baseline().baselineId(), policyId),
            """
                INSERT INTO business_confirmation_requests
                    (id, engagement_month_id, attendance_snapshot_id,
                     plan_version_id, baseline_id, certification_summary_id,
                     policy_version_id, version, status, transport_status,
                     quorum_mode, quorum_required, recipient_snapshot,
                     eligibility_snapshot, scope_manifest, scope_checksum,
                     requested_at, due_at, created_by_subject)
                VALUES (gen_random_uuid(), '%s'::uuid, '%s'::uuid, '%s'::uuid,
                        '%s'::uuid, '%s'::uuid, '%s'::uuid, 1,
                        'AWAITING_RESPONSE', 'NOT_CONFIGURED', 'ANY_ONE', 1,
                        '{}'::jsonb, '{}'::jsonb, '{}'::jsonb, repeat('d',64),
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '1 day',
                        'test')
                """.formatted(
                    NORTHSTAR_MONTH, completed.attendanceSnapshotId(),
                    completed.baseline().planVersionId(),
                    completed.baseline().baselineId(), completed.summaryId(),
                    policyId));
    }

    @Test
    void issuedRequestScopeDueAndHashBoundFieldsAreImmutable()
        throws Exception {
        F04TestSupport.DirectConfirmation request = directRequest();
        assertAllSqlRejected(
            """
                UPDATE business_confirmation_requests
                SET status = 'CANCELLED',
                    due_at = due_at + INTERVAL '1 day',
                    optimistic_version = optimistic_version + 1
                WHERE id = '%s'::uuid
                """.formatted(request.requestId()),
            """
                DELETE FROM business_confirmation_requests
                WHERE id = '%s'::uuid
                """.formatted(request.requestId()));
    }

    @Test
    void appendOnlyEvidenceOutboxAuditAndProviderFingerprintRejectMutationOrDuplicate()
        throws Exception {
        F04TestSupport.CompletedCertification completed = completed();
        UUID outcomeId = jdbc.queryForObject("""
            SELECT id FROM deliverable_delivery_outcomes WHERE submission_id = ?
            """, UUID.class, completed.submissionId());
        UUID certificationId = jdbc.queryForObject("""
            SELECT id FROM deliverable_certifications WHERE submission_id = ?
            """, UUID.class, completed.submissionId());
        assertAllSqlRejected(
            """
                UPDATE deliverable_delivery_outcomes
                SET delivery_summary = 'tampered'
                WHERE id = '%s'::uuid
                """.formatted(outcomeId),
            """
                DELETE FROM deliverable_certifications
                WHERE id = '%s'::uuid
                """.formatted(certificationId),
            """
                UPDATE certification_audit_events
                SET result = 'FORGED'
                WHERE engagement_month_id = '%s'::uuid
                """.formatted(MONTH));

        UUID outboxId = jdbc.queryForObject("""
            SELECT id FROM notification_outbox
            WHERE engagement_month_id = ?::uuid ORDER BY created_at LIMIT 1
            """, UUID.class, MONTH);
        assertAllSqlRejected(
            """
                UPDATE notification_outbox SET subject_text = 'tampered'
                WHERE id = '%s'::uuid
                """.formatted(outboxId),
            """
                INSERT INTO notification_outbox
                    (id, engagement_month_id, event_type, business_object_type,
                     business_object_id, business_object_version, idempotency_key,
                     correlation_id, template_key, template_version,
                     recipient_snapshot, subject_text, plain_text, html_text,
                     rendered_body_hash, archive_manifest_hash)
                SELECT gen_random_uuid(), engagement_month_id, event_type,
                       business_object_type, business_object_id,
                       business_object_version, idempotency_key, gen_random_uuid(),
                       template_key, template_version, recipient_snapshot,
                       subject_text, plain_text, html_text, rendered_body_hash,
                       archive_manifest_hash
                FROM notification_outbox WHERE id = '%s'::uuid
                """.formatted(outboxId));

        jdbc.update("""
            INSERT INTO inbound_confirmation_messages
                (id, provider_message_fingerprint, sender_address_hash,
                 classified_intent, status, correlation_id)
            VALUES (gen_random_uuid(), 'provider-fingerprint',
                    repeat('a',64), 'AMBIGUOUS',
                    'MANUAL_REVIEW_REQUIRED', gen_random_uuid())
            """);
        assertSqlRejected("""
            INSERT INTO inbound_confirmation_messages
                (id, provider_message_fingerprint, sender_address_hash,
                 classified_intent, status, correlation_id)
            VALUES (gen_random_uuid(), 'provider-fingerprint',
                    repeat('b',64), 'EXPLICIT_CONFIRM',
                    'MANUAL_REVIEW_REQUIRED', gen_random_uuid())
            """);
    }

    @Test
    void closureAndReopenEvidenceCannotBeCreatedIncompleteOrMutated()
        throws Exception {
        F04TestSupport.DirectConfirmation request = directRequest();
        assertSqlRejected("""
            INSERT INTO month_closures
                (id, engagement_month_id, version, confirmation_request_id,
                 manifest, manifest_hash, status, closed_by_subject)
            VALUES (gen_random_uuid(), '%s'::uuid, 1, '%s'::uuid,
                    '{}'::jsonb, repeat('e',64), 'CURRENT', 'test')
            """.formatted(MONTH, request.requestId()));

        jdbc.update("""
            UPDATE business_confirmation_requests
            SET status = 'CONFIRMED', completed_at = CURRENT_TIMESTAMP,
                optimistic_version = optimistic_version + 1
            WHERE id = ?
            """, request.requestId());
        Map<String, Object> closureManifest = new LinkedHashMap<>();
        closureManifest.put("schema", "f04-month-closure-v1");
        closureManifest.put("monthId", MONTH);
        closureManifest.put("monthVersion", 1);
        closureManifest.put("policyVersionId", request.policyId().toString());
        closureManifest.put("attendanceSnapshotId", null);
        closureManifest.put("attendanceExceptionId", "fixture-exception");
        closureManifest.put("planVersionId", "fixture-plan");
        closureManifest.put("baselineId", "fixture-baseline");
        closureManifest.put("baselineChecksum", "fixture-baseline-checksum");
        closureManifest.put("submissionId", "fixture-submission");
        closureManifest.put("submissionChecksum", "fixture-submission-checksum");
        closureManifest.put("summaryId", "fixture-summary");
        closureManifest.put("summaryVersion", 1);
        closureManifest.put("summaryChecksum", "fixture-summary-checksum");
        closureManifest.put(
            "confirmationRequestId", request.requestId().toString());
        closureManifest.put("confirmationVersion", 1);
        closureManifest.put("confirmationScopeChecksum", "fixture-scope");
        closureManifest.put("readinessRunId", "fixture-readiness");
        closureManifest.put("readinessInputHash", "fixture-readiness-hash");
        closureManifest.put("activeInvalidationCount", 0);
        closureManifest.put("f05HandoffId", null);
        closureManifest.put("f05PackageHash", null);
        CanonicalEvidenceHasher.HashResult closureHash =
            hasher.hash(closureManifest);
        UUID closureId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO month_closures
                (id, engagement_month_id, version, confirmation_request_id,
                 manifest, manifest_hash, status, closed_by_subject)
            VALUES (?, ?::uuid, 2, ?, ?::jsonb, ?,
                    'SUPERSEDED', 'test')
            """, closureId, MONTH, request.requestId(),
            closureHash.canonicalJson(), closureHash.checksum());
        assertAllSqlRejected(
            """
                UPDATE month_closures SET manifest = '{"tampered":true}'::jsonb
                WHERE id = '%s'::uuid
                """.formatted(closureId),
            """
                DELETE FROM month_closures WHERE id = '%s'::uuid
                """.formatted(closureId));
    }

    @Test
    void typedInvalidationFactIsAppendOnly()
        throws Exception {
        F04TestSupport.CompletedCertification completed =
            F04TestSupport.completedCertification(mvc, mapper, jdbc);
        UUID invalidation = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO certification_invalidations
                (id, engagement_month_id, object_type, object_id,
                 reason_code, status, correlation_id, created_by_subject)
            VALUES (?, ?::uuid, 'MONTHLY_CERTIFICATION_SUMMARY', ?,
                    'CORRECTION', 'ACTIVE', gen_random_uuid(), 'user-governance')
            """, invalidation, MONTH, completed.summaryId());
        assertSqlRejected("""
            UPDATE certification_invalidations
            SET status = 'CLEARED'
            WHERE id = '%s'::uuid
            """.formatted(invalidation));
        assertEquals("ACTIVE", jdbc.queryForObject("""
            SELECT effective_status
            FROM effective_certification_invalidations
            WHERE id = ?
            """, String.class, invalidation));
    }

    @Test
    void canonicalHashUsesStableKeysUtcAndDefinedListOrdering()
        throws Exception {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("z", List.of(
            Map.of("id", "b", "value", 2),
            Map.of("id", "a", "value", 1)));
        first.put("at", OffsetDateTime.parse("2026-07-31T23:30:00+05:30"));
        first.put("schema", "f04-test-v1");
        Map<String, Object> reorderedKeys = new LinkedHashMap<>();
        reorderedKeys.put("schema", "f04-test-v1");
        reorderedKeys.put("at", OffsetDateTime.parse("2026-07-31T18:00:00Z"));
        reorderedKeys.put("z", List.of(
            Map.of("value", 2, "id", "b"),
            Map.of("value", 1, "id", "a")));
        CanonicalEvidenceHasher.HashResult one = hasher.hash(first);
        CanonicalEvidenceHasher.HashResult two = hasher.hash(reorderedKeys);
        assertEquals(one.checksum(), two.checksum());
        assertEquals("SHA-256", one.algorithm());
        assertEquals(1, one.schemaVersion());

        Map<String, Object> changedListOrder = new LinkedHashMap<>(reorderedKeys);
        changedListOrder.put("z", List.of(
            Map.of("id", "a", "value", 1),
            Map.of("id", "b", "value", 2)));
        assertNotEquals(one.checksum(), hasher.hash(changedListOrder).checksum());
    }

    private F04TestSupport.CompletedCertification completed() throws Exception {
        return F04TestSupport.completedCertification(
            mvc, mapper, jdbc);
    }

    private F04TestSupport.DirectConfirmation directRequest() throws Exception {
        return F04TestSupport.directConfirmation(
            jdbc, completed(), "ANY_ONE", 1,
            OffsetDateTime.now().minusMinutes(1),
            OffsetDateTime.now().plusDays(2),
            List.of(new F04TestSupport.EligibleFixture(
                "user-reliance", "ravi@reliance.example", PROJECT_A)));
    }

    private UUID ensurePolicy(F04TestSupport.FrozenBaseline baseline) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO certification_policy_versions
                (id, engagement_id, version, status, attendance_required,
                 separation_of_duties_required, monthly_decision_required,
                 manual_second_review_required,
                 deemed_submission_approval_enabled,
                 deemed_certification_approval_enabled,
                 deemed_confirmation_approval_enabled, quorum_mode,
                 quorum_required, token_ttl_seconds,
                 confirmation_due_seconds, policy_hash, created_by_subject)
            VALUES (?, ?::uuid, 1, 'ACTIVE', true, true, true, true,
                    false, false, false, 'ANY_ONE', 1, 86400, 259200,
                    repeat('a',64), 'test')
            """, id, F04TestSupport.ENGAGEMENT);
        return id;
    }

    private void assertSqlRejected(String sql) {
        Boolean rejected = jdbc.execute(
            (org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection -> {
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
        assertTrue(Boolean.TRUE.equals(rejected),
            "PostgreSQL accepted an integrity-breaking statement: " + sql);
    }

    private void assertAllSqlRejected(String... statements) {
        assertAll(Arrays.stream(statements)
            .map(statement -> () -> assertSqlRejected(statement)));
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

}
