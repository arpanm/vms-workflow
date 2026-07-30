package com.vms.workflow.integration;

import com.vms.workflow.application.FinanceOperationsWorker;
import com.vms.workflow.infrastructure.AuthorizationStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;
import java.time.LocalDate;

import static com.vms.workflow.integration.F04TestSupport.ENGAGEMENT;
import static com.vms.workflow.integration.F04TestSupport.MONTH;
import static com.vms.workflow.integration.F04TestSupport.token;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:tc:vmspostgresql:18-alpine:///vms_workflow_finance_export_authorization_it",
    "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.flyway.locations=classpath:db/migration,classpath:db/testdata",
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:9/test-jwks",
    "vms.security.issuer=https://issuer.example.test",
    "vms.security.audience=vms-api",
    "vms.finance.local-scanner-enabled=true",
    "vms.finance.worker-enabled=true",
    "vms.finance.worker-initial-delay=PT1H"
})
@AutoConfigureMockMvc
@Transactional
class FinanceExportAuthorizationIT {
    private static final String AUDIT_SUBJECT = "user-governance-audit";
    private static final UUID AUDIT_PROFILE = UUID.fromString(
        "00000000-0000-0000-0000-000000000234");
    private static final UUID AUDIT_MEMBERSHIP = UUID.fromString(
        "00000000-0000-0000-0000-000000000334");
    private static final UUID AUDIT_ASSIGNMENT = UUID.fromString(
        "12000000-0000-0000-0000-000000000034");
    private static final UUID PROCUREMENT_ORGANIZATION = UUID.fromString(
        "00000000-0000-0000-0000-000000000103");
    private static final UUID GOVERNANCE_ROLE = UUID.fromString(
        "11000000-0000-0000-0000-000000000009");
    private static final String VENDOR_ONLY_SUBJECT = "user-vendor-export";
    private static final UUID VENDOR_ONLY_PROFILE = UUID.fromString(
        "00000000-0000-0000-0000-000000000235");
    private static final UUID VENDOR_ONLY_MEMBERSHIP = UUID.fromString(
        "00000000-0000-0000-0000-000000000335");
    private static final UUID VENDOR_ONLY_ASSIGNMENT = UUID.fromString(
        "12000000-0000-0000-0000-000000000035");
    private static final UUID VENDOR_ORGANIZATION = UUID.fromString(
        "00000000-0000-0000-0000-000000000101");
    private static final UUID VENDOR_MANAGER_ROLE = UUID.fromString(
        "11000000-0000-0000-0000-000000000003");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FinanceOperationsWorker worker;

    @Autowired
    private AuthorizationStore authorizationStore;

    @BeforeEach
    void createGovernanceAuditPersona() {
        jdbc.update("""
            INSERT INTO user_profiles(
                id, identity_subject, email, display_name,
                status, principal_type
            ) VALUES (?, ?, 'governance-audit@example.test',
                      'Grace Governance Audit', 'ACTIVE', 'HUMAN')
            """, AUDIT_PROFILE, AUDIT_SUBJECT);
        jdbc.update("""
            INSERT INTO memberships(
                id, user_profile_id, organization_id, role_code,
                status, valid_from
            ) VALUES (?, ?, ?, 'GOVERNANCE_REVIEWER',
                      'ACTIVE', '2020-01-01')
            """, AUDIT_MEMBERSHIP, AUDIT_PROFILE, PROCUREMENT_ORGANIZATION);
        jdbc.update("""
            INSERT INTO role_assignments(
                id, user_profile_id, organization_id, role_id,
                scope_type, scope_id, status, valid_from
            ) VALUES (?, ?, ?, ?, 'ENGAGEMENT', ?::uuid,
                      'ACTIVE', '2020-01-01')
            """, AUDIT_ASSIGNMENT, AUDIT_PROFILE, PROCUREMENT_ORGANIZATION,
            GOVERNANCE_ROLE, ENGAGEMENT);
        jdbc.update("""
            INSERT INTO user_profiles(
                id, identity_subject, email, display_name,
                status, principal_type
            ) VALUES (?, ?, 'vendor-export@example.test',
                      'Victor Vendor Export', 'ACTIVE', 'HUMAN')
            """, VENDOR_ONLY_PROFILE, VENDOR_ONLY_SUBJECT);
        jdbc.update("""
            INSERT INTO memberships(
                id, user_profile_id, organization_id, role_code,
                status, valid_from
            ) VALUES (?, ?, ?, 'VENDOR_MANAGER',
                      'ACTIVE', '2020-01-01')
            """, VENDOR_ONLY_MEMBERSHIP, VENDOR_ONLY_PROFILE,
            VENDOR_ORGANIZATION);
        jdbc.update("""
            INSERT INTO role_assignments(
                id, user_profile_id, organization_id, role_id,
                scope_type, scope_id, status, valid_from
            ) VALUES (?, ?, ?, ?, 'ENGAGEMENT', ?::uuid,
                      'ACTIVE', '2020-01-01')
            """, VENDOR_ONLY_ASSIGNMENT, VENDOR_ONLY_PROFILE,
            VENDOR_ORGANIZATION, VENDOR_MANAGER_ROLE, ENGAGEMENT);
    }

    @Test
    void restrictedExportLifecycleRevalidatesCurrentReportPermission()
        throws Exception {
        assertFalse(authorizationStore.hasEngagementPermission(
            VENDOR_ONLY_SUBJECT, UUID.fromString(ENGAGEMENT),
            "finance.audit.read", LocalDate.now()));
        UUID paymentExport = requestExport(
            "user-finance-ap", "PAYMENT_AGING", "payment-restricted");
        UUID auditExport = requestExport(
            AUDIT_SUBJECT, "COMMUNICATION_AUDIT", "audit-restricted");
        assertEquals(2, worker.processExports());

        assertLifecycleDenied(
            VENDOR_ONLY_SUBJECT, paymentExport, "PAYMENT_AGING");
        assertLifecycleDenied(
            VENDOR_ONLY_SUBJECT, auditExport, "COMMUNICATION_AUDIT");
        assertLifecycleDenied(
            "user-finance-ap", auditExport, "COMMUNICATION_AUDIT");
        assertLifecycleDenied(
            AUDIT_SUBJECT, paymentExport, "PAYMENT_AGING");

        assertStatusAndDownloadAllowed("user-finance-ap", paymentExport);
        assertStatusAndDownloadAllowed(AUDIT_SUBJECT, auditExport);

        jdbc.update("""
            UPDATE f05_report_exports
            SET status = 'DEAD_LETTER', progress = 0,
                last_error_code = 'TEST_REPLAY'
            WHERE id IN (?, ?)
            """, paymentExport, auditExport);
        assertReplayAllowed(
            "user-finance-ap", paymentExport, "payment-authorized-replay");
        assertReplayAllowed(
            AUDIT_SUBJECT, auditExport, "audit-authorized-replay");

        assertEquals(12, jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM f05_audit_events
            WHERE object_id IN (?, ?)
              AND result = 'DENIED'
              AND reason_code = 'REPORT_PERMISSION_DENIED'
            """, Integer.class, paymentExport, auditExport));
        assertEquals(12, jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM f05_security_events
            WHERE event_type = 'REPORT_EXPORT_ACCESS_DENIED'
              AND result = 'DENIED'
              AND reason_code = 'REPORT_PERMISSION_DENIED'
            """, Integer.class));
    }

    private UUID requestExport(
        String subject,
        String reportCode,
        String key
    ) throws Exception {
        String content = mvc.perform(post("/api/v1/finance/exports")
                .with(token(subject))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "reportId":"%s",
                      "reportVersion":"v1",
                      "format":"JSON",
                      "temporalMode":"CURRENT",
                      "filters":{
                        "engagementId":"%s",
                        "monthId":"%s"
                      },
                      "reason":"Restricted lifecycle authorization test"
                    }
                    """.formatted(reportCode, ENGAGEMENT, MONTH)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("QUEUED"))
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(
            mapper.readTree(content).path("exportId").asText());
    }

    private void assertLifecycleDenied(
        String subject,
        UUID exportId,
        String sensitiveReportCode
    ) throws Exception {
        var statusResult = mvc.perform(
                get("/api/v1/finance/exports/{id}", exportId)
                    .with(token(subject)))
            .andReturn().getResponse();
        assertEquals(403, statusResult.getStatus(),
            subject + " must not read restricted " + sensitiveReportCode
                + " export state");
        assertCorrelatedDenial(statusResult.getHeader("X-Correlation-Id"));
        String statusBody = statusResult.getContentAsString();
        var downloadResult = mvc.perform(
                post("/api/v1/finance/exports/{id}/download", exportId)
                    .with(token(subject)))
            .andReturn().getResponse();
        assertEquals(403, downloadResult.getStatus(),
            subject + " must not download restricted " + sensitiveReportCode
                + " exports");
        assertCorrelatedDenial(downloadResult.getHeader("X-Correlation-Id"));
        String downloadBody = downloadResult.getContentAsString();
        var replayResult = mvc.perform(
                post("/api/v1/finance/exports/{id}/replay", exportId)
                    .with(token(subject))
                    .header("Idempotency-Key",
                        "denied-" + subject + "-" + exportId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"reason":"This must be denied before state lookup"}
                        """))
            .andReturn().getResponse();
        assertEquals(403, replayResult.getStatus(),
            subject + " must not replay restricted " + sensitiveReportCode
                + " exports");
        assertCorrelatedDenial(replayResult.getHeader("X-Correlation-Id"));
        String replayBody = replayResult.getContentAsString();
        for (String body : new String[]{
                statusBody, downloadBody, replayBody}) {
            assertFalse(body.contains(sensitiveReportCode));
            assertFalse(body.contains("\"filters\""));
            assertFalse(body.contains("\"status\":\"READY\""));
        }
    }

    private void assertCorrelatedDenial(String correlationId) {
        UUID parsed = UUID.fromString(correlationId);
        assertEquals(1, jdbc.queryForObject("""
            SELECT count(*)::integer
            FROM f05_security_events
            WHERE correlation_id = ?
              AND result = 'DENIED'
            """, Integer.class, parsed));
    }

    private void assertStatusAndDownloadAllowed(
        String subject,
        UUID exportId
    ) throws Exception {
        mvc.perform(get("/api/v1/finance/exports/{id}", exportId)
                .with(token(subject)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("READY"));
        mvc.perform(post("/api/v1/finance/exports/{id}/download", exportId)
                .with(token(subject)))
            .andExpect(status().isOk());
    }

    private void assertReplayAllowed(
        String subject,
        UUID exportId,
        String idempotencyKey
    ) throws Exception {
        mvc.perform(post("/api/v1/finance/exports/{id}/replay", exportId)
                .with(token(subject))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"reason":"Authorized operational replay"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("QUEUED"));
    }
}
