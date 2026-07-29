package com.vms.workflow.integration;

import com.vms.workflow.application.MigrationDomainAdapter;
import com.vms.workflow.application.MigrationDomainAdapter.DomainEffect;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:tc:vmspostgresql:18-alpine:///vms_workflow",
    "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.flyway.locations=classpath:db/migration,classpath:db/testdata",
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:9/test-jwks",
    "vms.security.issuer=https://issuer.example.test",
    "vms.security.audience=vms-api",
    "vms.finance.cursor-signing-secret="
        + "migration-domain-adapter-test-secret-32-bytes"
})
@AutoConfigureMockMvc
@Transactional
class MigrationDomainAdapterIT {
    private static final UUID ENGAGEMENT =
        UUID.fromString("00000000-0000-0000-0000-000000000401");
    private static final UUID VENDOR =
        UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID JUNE =
        UUID.fromString("00000000-0000-0000-0000-000000000601");
    private static final UUID CERTIFICATION_MONTH =
        UUID.fromString(F04TestSupport.MONTH);
    private static final UUID SUMMARY =
        UUID.fromString("f0600000-0000-0000-0000-000000000005");
    private static final UUID CONFIRMATION =
        UUID.fromString("f0600000-0000-0000-0000-000000000006");
    private static final String ACTOR = "migration-domain-it";

    @Autowired
    private MigrationDomainAdapter adapter;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private MockMvc mvc;

    @Test
    void allFourteenPhysicalTemplatesApplyToTheirAuthoritativeDomains()
        throws Exception {
        List<DomainEffect> employees = apply("01_employees", values(
            "employee_number", "AF-MIG-ALL",
            "work_email", "af-mig-all@example.test",
            "join_date", "2026-05-01",
            "first_name", "All",
            "last_name", "Templates",
            "display_name", "All Templates",
            "designation", "Migration Engineer",
            "employment_status", "ACTIVE",
            "activation_status", "ENABLED",
            "attendance_source_mode", "INTERNAL_AUTHORITATIVE"
        ));
        UUID employee = oneUuid("""
            SELECT id FROM employees
            WHERE organization_id = ? AND employee_number = 'AF-MIG-ALL'
            """, VENDOR);
        assertEquals("af-mig-all@example.test", text("""
            SELECT work_email FROM employees WHERE id = ?
            """, employee));
        assertEquals("All Templates", text("""
            SELECT display_name FROM employee_versions
            WHERE employee_id = ? AND valid_to IS NULL
            """, employee));
        assertTrue(employees.stream().anyMatch(
            effect -> effect.table().equals("employees")));
        assertTrue(employees.stream().anyMatch(
            effect -> effect.table().equals("employee_versions")));

        DomainEffect allocation = only(apply(
            "02_employee_allocations", values(
                "employee_number", "AF-MIG-ALL",
                "project_code", "AGENTIC_SHOPOS",
                "valid_from", "2026-06-01",
                "valid_to", "",
                "allocation_percent", "80",
                "role_on_project", "Migration Engineer",
                "deployment_status", "ACTIVE"
            )));
        assertEquals("employee_project_allocations", allocation.table());
        assertEquals(new BigDecimal("80.00"), decimal("""
            SELECT allocation_percent
            FROM employee_project_allocations WHERE id = ?
            """, allocation.recordId()));

        List<DomainEffect> holiday = apply("03_holidays", values(
            "holiday_calendar_code", "AF-MIGRATION-2026",
            "calendar_version", "1",
            "holiday_date", "2026-06-20",
            "holiday_name", "Migration Test Holiday",
            "day_fraction", "HALF",
            "expected_minutes", "270"
        ));
        assertEquals("HALF_DAY_EXPECTED", text("""
            SELECT classification FROM calendar_holidays
            WHERE name = 'Migration Test Holiday'
            """));
        assertTrue(holiday.stream().anyMatch(
            effect -> effect.table().equals("working_calendar_versions")));
        assertTrue(holiday.stream().anyMatch(
            effect -> effect.table().equals("calendar_holidays")));

        DomainEffect override = only(apply(
            "04_employee_date_overrides", values(
                "employee_number", "AF-MIG-ALL",
                "override_date", "2026-06-21",
                "resulting_classification", "WEEKLY_OFF",
                "expected_minutes", "0",
                "reason", "Deterministic migration override"
            )));
        assertEquals("NON_WORKING", text("""
            SELECT classification FROM employee_date_overrides WHERE id = ?
            """, override.recordId()));

        DomainEffect balance = only(apply("05_leave_balances", values(
            "employee_number", "AF-MIG-ALL",
            "leave_type_code", "CL",
            "entry_type", "OPENING_BALANCE",
            "quantity_days", "4.5",
            "effective_date", "2026-06-01",
            "idempotency_reference", "f06-all-templates-balance",
            "reason", "Governed opening balance"
        )));
        assertEquals(new BigDecimal("4.50"), decimal("""
            SELECT quantity FROM leave_balance_ledger WHERE id = ?
            """, balance.recordId()));

        DomainEffect leave = only(apply("06_leave_requests", values(
            "employee_number", "AF-MIG-ALL",
            "leave_type_code", "CL",
            "quantity_days", "1.0",
            "leave_date", "2026-06-22",
            "decision_status", "APPROVED",
            "paid_lwp_classification", "PAID",
            "leave_request_external_id", "LR-F06-ALL-001",
            "reason", "Governed historical leave"
        )));
        assertEquals("APPROVED", text("""
            SELECT status FROM leave_requests WHERE id = ?
            """, leave.recordId()));
        assertEquals(new BigDecimal("1.00"), decimal("""
            SELECT paid_units FROM leave_requests WHERE id = ?
            """, leave.recordId()));

        List<DomainEffect> punches = apply(
            "07a_attendance_punches", values(
                "employee_number", "AF-MIG-ALL",
                "attendance_event_external_id", "EVT-F06-ALL-IN",
                "event_type", "CHECK_IN",
                "occurred_at", "2026-06-23T09:00:00+05:30",
                "timezone", "Asia/Kolkata"
            ));
        assertEquals("CHECK_IN", text("""
            SELECT event_type FROM attendance_events
            WHERE idempotency_key = 'EVT-F06-ALL-IN'
            """));
        assertEquals("OPEN", text("""
            SELECT status FROM attendance_sessions
            WHERE employee_id = ? AND work_date = '2026-06-23'
            """, employee));
        assertTrue(punches.stream().anyMatch(
            effect -> effect.table().equals("attendance_events")));
        assertTrue(punches.stream().anyMatch(
            effect -> effect.table().equals("attendance_sessions")));

        DomainEffect day = only(apply("07b_attendance_daily", values(
            "employee_number", "AF-MIG-ALL",
            "attendance_date", "2026-06-24",
            "calendar_classification", "WORKING",
            "expected_minutes", "540",
            "net_worked_minutes", "510",
            "paid_leave_days", "0",
            "paid_leave_type_code", "",
            "final_attendance_status", "PRESENT_FULL_DAY",
            "exception_code", ""
        )));
        assertEquals("attendance_days", day.table());
        assertEquals("HISTORICAL_IMPORT", text("""
            SELECT source_mode FROM attendance_days WHERE id = ?
            """, day.recordId()));
        assertTrue(bool("""
            SELECT is_current FROM attendance_days WHERE id = ?
            """, day.recordId()));

        List<DomainEffect> deliverable = apply("08_deliverables", values(
            "billing_month", "2026-06",
            "plan_external_id", "PLAN-F06-ALL",
            "plan_version", "1",
            "represented_plan_approved_at", "2026-05-30T10:00:00Z",
            "deliverable_code", "F06-ALL-001",
            "project_code", "AGENTIC_SHOPOS",
            "title", "All-template integration deliverable",
            "description", "Exercises complete migration semantics",
            "business_objective", "Prove all physical templates",
            "product_owner_email", "ravi@reliance.example",
            "vendor_owner_email", "alice@arrowfoundry.example",
            "priority", "P1",
            "target_completion_date", "2026-06-30",
            "delivery_category", "PLATFORM",
            "acceptance_criteria", "Mapped|Persisted;Verified",
            "evidence_expectations", "Database assertions",
            "dependencies", "External archive|Legacy issue export",
            "risks_and_assumptions", "Synthetic local prerequisites",
            "assigned_employee_numbers", "AF-MIG-ALL"
        ));
        UUID deliverableVersion = oneUuid("""
            SELECT version.id
            FROM delivery_deliverable_versions version
            JOIN delivery_deliverables deliverable
              ON deliverable.id = version.deliverable_id
            JOIN delivery_plans plan ON plan.id = deliverable.plan_id
            WHERE plan.engagement_month_id = ?
              AND deliverable.deliverable_code = 'F06-ALL-001'
            """, JUNE);
        assertEquals("user-reliance", text("""
            SELECT product_owner_subject FROM delivery_deliverable_versions
            WHERE id = ?
            """, deliverableVersion));
        assertEquals(3, count("""
            SELECT count(*) FROM delivery_acceptance_criteria
            WHERE deliverable_version_id = ?
            """, deliverableVersion));
        assertEquals(2, count("""
            SELECT count(*) FROM delivery_dependencies
            WHERE deliverable_version_id = ?
            """, deliverableVersion));
        assertEquals(1, count("""
            SELECT count(*) FROM delivery_employee_assignments
            WHERE deliverable_version_id = ? AND employee_id = ?
            """, deliverableVersion, employee));
        assertTrue(deliverable.size() >= 9);

        List<DomainEffect> linear = apply(
            "09_deliverable_linear_links", values(
                "deliverable_code", "F06-ALL-001",
                "linear_issue_url",
                    "https://linear.app/test/issue/F06-ALL-1",
                "linear_issue_identifier", "F06-ALL-1",
                "linear_issue_uuid",
                    "f0600000-0000-0000-0000-000000000009",
                "historical_snapshot_at", "2026-06-30T18:29:00Z",
                "historical_state_name", "Done",
                "historical_state_type", "COMPLETED",
                "source_system", "LINEAR_EXPORT"
            ));
        assertEquals(2, linear.size());
        assertEquals("COMPLETED", text("""
            SELECT snapshot.normalized_state
            FROM linear_issue_snapshots snapshot
            JOIN linear_issue_links link ON link.id = snapshot.issue_link_id
            WHERE link.identifier = 'F06-ALL-1'
            """));
        assertEquals("HISTORICAL_RETRIEVAL", text("""
            SELECT snapshot.snapshot_type
            FROM linear_issue_snapshots snapshot
            JOIN linear_issue_links link ON link.id = snapshot.issue_link_id
            WHERE link.identifier = 'F06-ALL-1'
            """));

        F04TestSupport.FrozenBaseline certifiedBaseline =
            F04TestSupport.frozenBaseline(mvc, mapper);
        var certificationWorkspace =
            F04TestSupport.workspace(mvc, mapper, "user-arrow");
        var certificationDraft = F04TestSupport.saveCompleteDraft(
            mvc, mapper, certifiedBaseline, "user-arrow",
            certificationWorkspace.path("version").asLong(),
            "f06-all-template-certification-draft");
        UUID certificationSubmission = UUID.fromString(
            certificationDraft.path("submission").path("id").asText());
        F04TestSupport.submit(
            mvc, mapper, certificationSubmission, 1,
            "f06-all-template-certification-submit");

        List<DomainEffect> certification = apply(
            CERTIFICATION_MONTH,
            "10_delivery_certifications", values(
                "deliverable_code", "F04-001",
                "vendor_summary", "Delivered and evidenced",
                "client_certification_decision",
                    "ACCEPTED_WITH_OBSERVATIONS",
                "client_certification_comment",
                    "Accepted with tracked hardening",
                "product_owner_email", "ravi@reliance.example",
                "represented_certification_at", "2026-06-30T17:00:00Z"
            ));
        assertEquals(2, certification.size());
        assertEquals("user-reliance", text("""
            SELECT decided_by_subject FROM deliverable_certifications
            WHERE submission_id = ?
            """, certificationSubmission));
        assertEquals("ACCEPTED_WITH_OBSERVATIONS", text("""
            SELECT decision FROM deliverable_certifications
            WHERE submission_id = ?
            """, certificationSubmission));

        seedConfirmationPrerequisites(
            certifiedBaseline, certificationSubmission);

        DomainEffect confirmation = only(apply(
            CERTIFICATION_MONTH,
            "11_business_confirmations", values(
                "actor_email", "ravi@reliance.example",
                "represented_response_at", "2026-07-02T09:30:00Z",
                "decision", "CONFIRMED",
                "review_comment", "Historical response verified"
            )));
        assertEquals("business_confirmation_actions", confirmation.table());
        assertEquals("user-reliance", text("""
            SELECT actor_subject FROM business_confirmation_actions
            WHERE id = ?
            """, confirmation.recordId()));
        assertEquals("CONFIRM", text("""
            SELECT action FROM business_confirmation_actions WHERE id = ?
            """, confirmation.recordId()));

        List<DomainEffect> invoice = apply("12_invoices", values(
            "invoice_number", "F06-ALL-INV-001",
            "invoice_date", "2026-07-01",
            "billing_start_date", "2026-06-01",
            "billing_end_date", "2026-06-30",
            "currency", "INR",
            "taxable_value", "100000.00",
            "tax_amount", "18000.00",
            "total_amount", "118000.00",
            "po_reference", "PO-F06-ALL",
            "work_order_reference", "WO-F06-ALL",
            "invoice_filename", "f06-all-invoice.pdf",
            "invoice_sha256",
                "abcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcd",
            "represented_uploaded_at", "2026-07-01T06:30:00Z"
        ));
        UUID invoiceId = oneUuid("""
            SELECT id FROM invoices
            WHERE invoice_number = 'F06-ALL-INV-001'
            """);
        assertEquals(new BigDecimal("118000.0000"), decimal("""
            SELECT total_value FROM invoices WHERE id = ?
            """, invoiceId));
        assertNotNull(oneUuid("""
            SELECT document_artifact_id FROM invoice_versions
            WHERE invoice_id = ?
            """, invoiceId));
        assertEquals("HISTORICAL_MIGRATION", text("""
            SELECT artifact.source
            FROM invoice_versions version
            JOIN f05_private_artifacts artifact
              ON artifact.id = version.document_artifact_id
            WHERE version.invoice_id = ?
            """, invoiceId));
        assertTrue(invoice.stream().anyMatch(
            effect -> effect.table().equals("invoices")));
        assertTrue(invoice.stream().anyMatch(
            effect -> effect.table().equals("invoice_versions")));

        DomainEffect approval = only(apply(
            "13_approval_history", values(
                "object_type", "MONTHLY_PLAN",
                "object_external_id", "PLAN-F06-ALL",
                "object_version", "1",
                "decision", "APPROVED",
                "actor_email", "ravi@reliance.example",
                "actor_organization_code", "RELIANCE_INTELLIGENCE",
                "actor_role", "CLIENT_PRODUCT_OWNER",
                "represented_at", "2026-05-30T16:00:00Z",
                "comment", "Approved for represented June execution",
                "evidence_reference", "email://f06-all-plan-approval"
            )));
        assertEquals("certification_audit_events", approval.table());
        assertEquals("user-reliance", text("""
            SELECT actor_subject FROM certification_audit_events WHERE id = ?
            """, approval.recordId()));
        assertEquals("CLIENT_PRODUCT_OWNER", text("""
            SELECT authority_snapshot #>> '{historicalAuthority,roleCode}'
            FROM certification_audit_events WHERE id = ?
            """, approval.recordId()));
        assertFalse(approval.beforeState().containsKey("salary"));
    }

    private void seedConfirmationPrerequisites(
        F04TestSupport.FrozenBaseline baseline,
        UUID submission
    ) {
        UUID policy = oneUuid("""
            SELECT policy_version_id FROM delivery_submissions WHERE id = ?
            """, submission);
        UUID round = oneUuid("""
            SELECT id FROM certification_rounds
            WHERE submission_id = ? ORDER BY round_number DESC LIMIT 1
            """, submission);
        jdbc.update("""
            INSERT INTO monthly_certification_summaries
              (id, engagement_month_id, submission_id, round_id,
               plan_version_id, baseline_id, policy_version_id, version,
               status, monthly_decision, manifest, checksum,
               authority_snapshot, represented_at, created_by_subject)
            VALUES (?, ?, ?, ?, ?, ?, ?, 1, 'CURRENT', 'CERTIFIED',
                    '{}'::jsonb, repeat('d', 64),
                    '{"subject":"user-reliance"}'::jsonb,
                    '2026-06-30T18:00:00Z', ?)
            """, SUMMARY, CERTIFICATION_MONTH, submission, round,
            baseline.planVersionId(), baseline.baselineId(), policy, ACTOR);
        jdbc.update("""
            INSERT INTO business_confirmation_requests
              (id, engagement_month_id, plan_version_id, baseline_id,
               certification_summary_id, policy_version_id, version, status,
               transport_status, quorum_mode, quorum_required,
               recipient_snapshot, eligibility_snapshot, scope_manifest,
               scope_checksum, requested_at, due_at, created_by_subject)
            VALUES (?, ?, ?, ?, ?, ?, 1, 'AWAITING_RESPONSE',
                    'NOT_CONFIGURED', 'ANY_ONE', 1, '{}'::jsonb,
                    '{}'::jsonb, '{}'::jsonb, repeat('e', 64),
                    '2026-07-01T00:00:00Z', '2026-07-08T00:00:00Z', ?)
            """, CONFIRMATION, CERTIFICATION_MONTH,
            baseline.planVersionId(), baseline.baselineId(), SUMMARY, policy,
            ACTOR);
        UUID project = oneUuid("""
            SELECT project_id FROM delivery_deliverable_versions WHERE id = ?
            """, baseline.deliverableVersionId());
        UUID eligibility = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO confirmation_eligibility_snapshots
              (id, engagement_month_id, policy_version_id,
               eligible_confirmer_subject, verified_email, project_id,
               sequence_number, authority_snapshot)
            VALUES (?, ?, ?, 'user-reliance', 'ravi@reliance.example', ?,
                    1, '{"source":"F06_DOMAIN_ADAPTER_IT"}'::jsonb)
            """, eligibility, CERTIFICATION_MONTH, policy, project);
        jdbc.update("""
            INSERT INTO confirmation_request_eligibility
              (request_id, eligibility_id, eligible_confirmer_subject,
               project_id, sequence_number)
            VALUES (?, ?, 'user-reliance', ?, 1)
            """, CONFIRMATION, eligibility, project);
    }

    private List<DomainEffect> apply(
        String template,
        Map<String, String> payload
    ) {
        return apply(JUNE, template, payload);
    }

    private List<DomainEffect> apply(
        UUID monthId,
        String template,
        Map<String, String> payload
    ) {
        return adapter.apply(
            template, ENGAGEMENT, VENDOR, monthId,
            mapper.writeValueAsString(payload), ACTOR, "f06-it-" + template);
    }

    private Map<String, String> values(String... entries) {
        assertEquals(0, entries.length % 2);
        Map<String, String> result = new java.util.LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put(entries[index], entries[index + 1]);
        }
        return result;
    }

    private DomainEffect only(List<DomainEffect> effects) {
        assertEquals(1, effects.size());
        return effects.getFirst();
    }

    private int count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Integer.class, arguments);
    }

    private UUID oneUuid(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, UUID.class, arguments);
    }

    private String text(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, String.class, arguments);
    }

    private BigDecimal decimal(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, BigDecimal.class, arguments);
    }

    private boolean bool(String sql, Object... arguments) {
        return Boolean.TRUE.equals(
            jdbc.queryForObject(sql, Boolean.class, arguments));
    }
}
