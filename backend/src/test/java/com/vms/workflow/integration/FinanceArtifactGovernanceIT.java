package com.vms.workflow.integration;

import com.vms.workflow.application.FinanceCanonicalJson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static com.vms.workflow.integration.F04TestSupport.ENGAGEMENT;
import static com.vms.workflow.integration.F04TestSupport.MONTH;
import static com.vms.workflow.integration.F04TestSupport.token;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
    "vms.finance.worker-initial-delay=PT1H"
})
@AutoConfigureMockMvc
@Transactional
class FinanceArtifactGovernanceIT {
    private static final String GOVERNANCE = "user-finance-governance";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FinanceCanonicalJson canonical;

    @BeforeEach
    void governanceAuthority() {
        jdbc.update("""
            INSERT INTO user_profiles(
                id, identity_subject, email, display_name,
                status, principal_type
            ) VALUES (
                '00000000-0000-0000-0000-000000000239',
                ?, 'finance-governance@example.test',
                'Finance Governance', 'ACTIVE', 'HUMAN'
            ) ON CONFLICT DO NOTHING
            """, GOVERNANCE);
        jdbc.update("""
            INSERT INTO memberships(
                id, user_profile_id, organization_id, role_code,
                status, valid_from
            ) VALUES (
                '00000000-0000-0000-0000-000000000339',
                '00000000-0000-0000-0000-000000000239',
                '00000000-0000-0000-0000-000000000103',
                'GOVERNANCE_REVIEWER', 'ACTIVE', '2020-01-01'
            ) ON CONFLICT DO NOTHING
            """);
        jdbc.update("""
            INSERT INTO role_assignments(
                id, user_profile_id, organization_id, role_id,
                scope_type, scope_id, status, valid_from
            ) VALUES (
                '12000000-0000-0000-0000-000000000039',
                '00000000-0000-0000-0000-000000000239',
                '00000000-0000-0000-0000-000000000103',
                '11000000-0000-0000-0000-000000000009',
                'ENGAGEMENT', ?::uuid, 'ACTIVE', '2020-01-01'
            ) ON CONFLICT DO NOTHING
            """, ENGAGEMENT);
    }

    @Test
    void authorizedLegalHoldIsLedgeredAuditedAndIdempotent()
        throws Exception {
        UUID artifactId = insertArtifact("PENDING");
        String body = """
            {"enabled":true,"reasonCode":"litigation_case_42"}
            """;

        mvc.perform(post(
                    "/api/v1/finance/artifacts/{artifactId}/legal-hold",
                    artifactId)
                .with(token(GOVERNANCE))
                .header("Idempotency-Key", "hold-" + artifactId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.artifactId")
                .value(artifactId.toString()))
            .andExpect(jsonPath("$.legalHold").value(true))
            .andExpect(jsonPath("$.reasonCode")
                .value("LITIGATION_CASE_42"));

        mvc.perform(post(
                    "/api/v1/finance/artifacts/{artifactId}/legal-hold",
                    artifactId)
                .with(token(GOVERNANCE))
                .header("Idempotency-Key", "hold-" + artifactId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.legalHold").value(true));

        assertEquals(1, count("""
            SELECT COUNT(*) FROM f05_artifact_hold_transitions
            WHERE artifact_id = ? AND applied_at IS NOT NULL
            """, artifactId));
        assertEquals(1, count("""
            SELECT COUNT(*) FROM f05_audit_events
            WHERE object_id = ?
              AND action = 'ARTIFACT_LEGAL_HOLD_CHANGED'
              AND authority_snapshot ->> 'permission'
                  = 'artifact.legal-hold.manage'
            """, artifactId));
    }

    @Test
    void legalHoldRejectsCallerWithoutSpecificAuthority()
        throws Exception {
        UUID artifactId = insertArtifact("PENDING");

        mvc.perform(post(
                    "/api/v1/finance/artifacts/{artifactId}/legal-hold",
                    artifactId)
                .with(token("user-procurement"))
                .header("Idempotency-Key", "unauthorized-" + artifactId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"enabled":true,"reasonCode":"LEGAL_REQUEST"}
                    """))
            .andExpect(status().isForbidden());
        assertEquals(0, count("""
            SELECT COUNT(*) FROM f05_artifact_hold_transitions
            WHERE artifact_id = ?
            """, artifactId));
    }

    @Test
    void scannerTransitionCreatesIndependentAuditRecord() {
        UUID artifactId = insertArtifact("PENDING");

        jdbc.update("""
            UPDATE f05_private_artifacts
            SET scan_status = 'PASSED',
                scan_engine = 'TEST_SCANNER',
                scan_reason_code = 'CLEAN',
                scanned_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """, artifactId);

        assertEquals(1, count("""
            SELECT COUNT(*)
            FROM f05_audit_events
            WHERE object_id = ?
              AND action = 'ARTIFACT_SCAN_STATE_CHANGED'
              AND actor_subject = 'system:finance-scanner'
              AND evidence_references @>
                  '[{"fromStatus":"PENDING","toStatus":"PASSED"}]'::jsonb
            """, artifactId));
    }

    private UUID insertArtifact(String scanStatus) {
        UUID artifactId = UUID.randomUUID();
        byte[] content = "%PDF-1.7\ncontrolled artifact\n%%EOF".getBytes();
        jdbc.update("""
            INSERT INTO f05_private_artifacts(
                id, engagement_month_id, owner_organization_id,
                logical_type, safe_name, media_type, byte_size,
                content_hash, object_key, object_version,
                classification, retention_class, scan_status,
                provider_status, source, uploaded_by_subject,
                correlation_id
            ) VALUES (
                ?, ?::uuid,
                '00000000-0000-0000-0000-000000000101',
                'INVOICE_DOCUMENT', 'controlled.pdf', 'application/pdf',
                ?, ?, ?, 'controlled-v1', 'CONFIDENTIAL',
                'FINANCE_EVIDENCE', ?, 'CONFIGURED', 'TEST',
                'user-arrow', ?
            )
            """, artifactId, MONTH, content.length,
            canonical.sha256Bytes(content), "test/" + artifactId,
            scanStatus, UUID.randomUUID());
        return artifactId;
    }

    private int count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Integer.class, arguments);
    }
}
