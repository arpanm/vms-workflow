package com.vms.workflow.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:tc:vmspostgresql:18-alpine:///vms_workflow_greythr_schema",
    "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.flyway.locations=classpath:db/migration,classpath:db/testdata",
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:9/test-jwks",
    "vms.security.issuer=https://issuer.example.test",
    "vms.security.audience=vms-api"
})
class GreytHrSchemaHardeningIT {
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void compositeKeysRejectCrossTenantCertificationRunFactAndReconciliation()
        throws Exception {
        List<TenantEmployee> tenants = tenantEmployees();
        TenantEmployee first = tenants.get(0);
        TenantEmployee second = isolatedTenantEmployee();
        UUID connectionId = connection(first.organizationId());
        UUID foreignCertification = certification(second.organizationId());

        assertThrows(DataAccessException.class, () -> jdbc.update("""
            UPDATE greythr_connections
            SET capability_certification_id = ?, status = 'ACTIVE'
            WHERE id = ?
            """, foreignCertification, connectionId));

        UUID crossTenantRun = UUID.randomUUID();
        assertThrows(DataAccessException.class, () -> jdbc.update("""
            INSERT INTO greythr_sync_runs(
                id, connection_id, organization_id, idempotency_key,
                request_hash, date_from, date_to, status, correlation_id
            ) VALUES (?, ?, ?, 'cross-tenant', ?, CURRENT_DATE,
                      CURRENT_DATE, 'RUNNING', ?)
            """, crossTenantRun, connectionId, second.organizationId(),
            "a".repeat(64), UUID.randomUUID()));

        UUID runId = runningRun(connectionId, first.organizationId());
        assertThrows(DataAccessException.class, () -> jdbc.update("""
            INSERT INTO greythr_imported_facts(
                id, connection_id, sync_run_id, organization_id, employee_id,
                provider_employee_id, fact_kind, work_date,
                provider_record_id, payload_hash, source_updated_at, payload
            ) VALUES (?, ?, ?, ?, ?, 'GHR-CROSS', 'ATTENDANCE',
                      CURRENT_DATE, 'cross-tenant-fact', ?,
                      CURRENT_TIMESTAMP, ?::jsonb)
            """, UUID.randomUUID(), connectionId, runId,
            first.organizationId(), second.employeeId(), "b".repeat(64),
            attendancePayload("GHR-CROSS", "cross-tenant-fact")));

        UUID factId = attendanceFact(
            connectionId, runId, first, "GHR-LOCAL", "local-fact",
            "c".repeat(64));
        assertThrows(DataAccessException.class, () -> jdbc.update("""
            INSERT INTO greythr_reconciliation_items(
                id, sync_run_id, connection_id, organization_id, employee_id,
                work_date, conflict_type, provider_fact_id
            ) VALUES (?, ?, ?, ?, ?, CURRENT_DATE,
                      'ATTENDANCE_SOURCE_CONFLICT', ?)
            """, UUID.randomUUID(), runId, connectionId,
            first.organizationId(), second.employeeId(), factId));
    }

    @Test
    void certificationReconciliationCutoverMappingAndApplicationEvidenceAreImmutable()
        throws Exception {
        TenantEmployee tenant = tenantEmployees().get(0);
        UUID connectionId = connection(tenant.organizationId());
        UUID certificationId = attestedCertification(
            connectionId, tenant.organizationId());
        UUID certificationEvidence = jdbc.queryForObject("""
            SELECT id FROM greythr_certification_evidence
            WHERE connection_id = ?
            """, UUID.class, connectionId);

        UUID runId = runningRun(connectionId, tenant.organizationId());
        UUID employeeFact = employeeFact(
            connectionId, runId, tenant, "employee-v1", "d".repeat(64),
            "2026-07-10T10:00:00Z");
        UUID mappingId = jdbc.queryForObject("""
            SELECT id FROM greythr_employee_mappings
            WHERE source_fact_id = ?
            """, UUID.class, employeeFact);
        UUID attendanceFact = attendanceFact(
            connectionId, runId, tenant, "GHR-MAPPED",
            "attendance-v1", "e".repeat(64));
        UUID reconciliationId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO greythr_reconciliation_items(
                id, sync_run_id, connection_id, organization_id, employee_id,
                work_date, conflict_type, provider_fact_id
            ) VALUES (?, ?, ?, ?, ?, CURRENT_DATE,
                      'ATTENDANCE_SOURCE_CONFLICT', ?)
            """, reconciliationId, runId, connectionId,
            tenant.organizationId(), tenant.employeeId(), attendanceFact);
        jdbc.update("""
            UPDATE greythr_reconciliation_items
            SET status = 'KEEP_INTERNAL', decision_reason = 'Explicit review',
                decided_at = CURRENT_TIMESTAMP,
                decided_by_subject = 'schema-test'
            WHERE id = ?
            """, reconciliationId);
        assertEquals(2, jdbc.queryForObject("""
            SELECT count(*) FROM greythr_reconciliation_transitions
            WHERE reconciliation_id = ?
            """, Integer.class, reconciliationId));
        UUID transitionId = jdbc.queryForObject("""
            SELECT id FROM greythr_reconciliation_transitions
            WHERE reconciliation_id = ?
            ORDER BY transition_sequence DESC LIMIT 1
            """, UUID.class, reconciliationId);

        jdbc.update("""
            UPDATE attendance_source_mode_assignments
            SET valid_to = CURRENT_DATE - 1
            WHERE employee_id = ? AND valid_to IS NULL
            """, tenant.employeeId());
        UUID sourceAssignment = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO attendance_source_mode_assignments(
                id, employee_id, mode, authoritative_source,
                capability_certification_id, valid_from, created_by_subject
            ) VALUES (?, ?, 'GREYTHR_AUTHORITATIVE', 'GREYTHR', ?,
                      CURRENT_DATE, 'schema-test')
            """, sourceAssignment, tenant.employeeId(), certificationId);
        UUID cutoverId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO greythr_cutovers(
                id, connection_id, organization_id, employee_id,
                capability_certification_id, source_assignment_id,
                effective_from, reason, created_by_subject
            ) VALUES (?, ?, ?, ?, ?, ?, CURRENT_DATE,
                      'Explicit test cutover', 'schema-test')
            """, cutoverId, connectionId, tenant.organizationId(),
            tenant.employeeId(), certificationId, sourceAssignment);

        UUID applicationId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO greythr_fact_applications(
                id, connection_id, organization_id, provider_fact_id,
                action, target_kind, target_record_id, correlation_id,
                reason, applied_by_subject
            ) VALUES (?, ?, ?, ?, 'APPLY', 'ATTENDANCE_SESSION', ?, ?,
                      'Schema lineage test', 'schema-test')
            """, applicationId, connectionId, tenant.organizationId(),
            attendanceFact, UUID.randomUUID(), UUID.randomUUID());

        for (Mutation mutation : List.of(
            new Mutation("greythr_certification_evidence", certificationEvidence),
            new Mutation("greythr_reconciliation_transitions", transitionId),
            new Mutation("greythr_employee_mappings", mappingId),
            new Mutation("greythr_imported_facts", attendanceFact),
            new Mutation("greythr_cutovers", cutoverId),
            new Mutation("greythr_fact_applications", applicationId)
        )) {
            assertRuntimeMutationDenied(mutation);
        }
    }

    @Test
    void correctedFactsAndProviderMappingsHaveForwardOnlyVersionLineage() {
        TenantEmployee tenant = tenantEmployees().get(0);
        UUID connectionId = connection(tenant.organizationId());
        UUID runId = runningRun(connectionId, tenant.organizationId());
        UUID first = employeeFact(
            connectionId, runId, tenant, "employee-stable",
            "1".repeat(64), "2026-07-10T10:00:00Z");
        UUID second = employeeFact(
            connectionId, runId, tenant, "employee-stable",
            "2".repeat(64), "2026-07-10T11:00:00Z");

        assertEquals(first, jdbc.queryForObject("""
            SELECT supersedes_id FROM greythr_imported_facts WHERE id = ?
            """, UUID.class, second));
        assertEquals(List.of(1, 2), jdbc.queryForList("""
            SELECT mapping_version
            FROM greythr_employee_mappings
            WHERE connection_id = ? AND provider_employee_id = 'GHR-MAPPED'
            ORDER BY mapping_version
            """, Integer.class, connectionId));
        assertEquals(1, jdbc.queryForObject("""
            SELECT count(*)
            FROM greythr_employee_mappings current_mapping
            JOIN greythr_employee_mappings prior
              ON prior.id = current_mapping.supersedes_mapping_id
            WHERE current_mapping.source_fact_id = ?
              AND prior.source_fact_id = ?
            """, Integer.class, second, first));

        assertThrows(DataAccessException.class, () -> employeeFact(
            connectionId, runId, tenant, "employee-stable",
            "3".repeat(64), "2026-07-10T09:59:59Z"));
    }

    @Test
    void directWritesRejectNestedCommercialAndRestrictedProviderKeys() {
        TenantEmployee tenant = tenantEmployees().get(0);
        UUID connectionId = connection(tenant.organizationId());
        for (String prohibited : List.of(
            "compensation", "payroll", "bankAccount", "CTC"
        )) {
            assertThrows(DataAccessException.class, () -> jdbc.update("""
                INSERT INTO greythr_recorded_pages(
                    connection_id, page_number, payload, source_updated_at
                ) VALUES (?, ?, ?::jsonb, CURRENT_TIMESTAMP)
                """, connectionId,
                Math.abs(prohibited.hashCode() % 90) + 1,
                """
                {
                  "employees":[],
                  "nested":{"%s":"restricted"}
                }
                """.formatted(prohibited)));
        }
    }

    private void assertRuntimeMutationDenied(Mutation mutation)
        throws Exception {
        try (Connection connection = jdbc.getDataSource().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE vms_app_runtime");
            assertThrows(java.sql.SQLException.class, () -> statement.execute(
                "UPDATE " + mutation.table()
                    + " SET id = gen_random_uuid() WHERE id = '"
                    + mutation.id() + "'"));
            assertThrows(java.sql.SQLException.class, () -> statement.execute(
                "DELETE FROM " + mutation.table() + " WHERE id = '"
                    + mutation.id() + "'"));
        }
    }

    private UUID connection(UUID organizationId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO greythr_connections(
                id, organization_id, display_name, status, adapter_mode,
                created_by_subject
            ) VALUES (?, ?, ?, 'DISCOVERED', 'RECORDED_FIXTURE', 'schema-test')
            """, id, organizationId, "Schema test " + id);
        return id;
    }

    private UUID certification(UUID organizationId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO integration_capability_certifications(
                id, organization_id, provider, status, certified_at,
                capability_manifest
            ) VALUES (?, ?, 'GREYTHR', 'CERTIFIED', CURRENT_TIMESTAMP,
                      '{"capabilities":["EMPLOYEES","ATTENDANCE","LEAVE"]}'::jsonb)
            """, id, organizationId);
        return id;
    }

    private UUID attestedCertification(
        UUID connectionId,
        UUID organizationId
    ) {
        UUID probeId = UUID.randomUUID();
        String hash = "e".repeat(64);
        jdbc.update("""
            INSERT INTO greythr_capability_probe_evidence(
                id, connection_id, organization_id, adapter_mode,
                status, capabilities, evidence_manifest, evidence_hash,
                probed_at
            ) VALUES (?, ?, ?, 'RECORDED_FIXTURE', 'PASSED',
                      '["ATTENDANCE","EMPLOYEES","LEAVE"]'::jsonb,
                      '{"authority":"SIMULATED_NON_PRODUCTION"}'::jsonb,
                      ?, CURRENT_TIMESTAMP)
            """, probeId, connectionId, organizationId, hash);
        UUID certificationId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO integration_capability_certifications(
                id, organization_id, provider, status, certified_at,
                capability_manifest
            ) VALUES (?, ?, 'GREYTHR', 'CERTIFIED', CURRENT_TIMESTAMP,
                      ?::jsonb)
            """, certificationId, organizationId, """
            {
              "schema":"greythr-capability-v2",
              "capabilities":["ATTENDANCE","EMPLOYEES","LEAVE"],
              "providerAttestation":{
                "probeEvidenceId":"%s",
                "evidenceHash":"%s",
                "adapterMode":"RECORDED_FIXTURE"
              }
            }
            """.formatted(probeId, hash));
        jdbc.update("""
            UPDATE greythr_connections
            SET capability_certification_id = ?, status = 'ACTIVE'
            WHERE id = ?
            """, certificationId, connectionId);
        return certificationId;
    }

    private UUID runningRun(UUID connectionId, UUID organizationId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO greythr_sync_runs(
                id, connection_id, organization_id, idempotency_key,
                request_hash, date_from, date_to, status, correlation_id
            ) VALUES (?, ?, ?, ?, ?, CURRENT_DATE, CURRENT_DATE,
                      'RUNNING', ?)
            """, id, connectionId, organizationId, "schema-" + id,
            "f".repeat(64), UUID.randomUUID());
        return id;
    }

    private UUID employeeFact(
        UUID connectionId,
        UUID runId,
        TenantEmployee tenant,
        String recordId,
        String hash,
        String sourceUpdatedAt
    ) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO greythr_imported_facts(
                id, connection_id, sync_run_id, organization_id, employee_id,
                provider_employee_id, fact_kind, provider_record_id,
                payload_hash, source_updated_at, payload
            ) VALUES (?, ?, ?, ?, ?, 'GHR-MAPPED', 'EMPLOYEE', ?, ?,
                      ?::timestamptz, ?::jsonb)
            """, id, connectionId, runId, tenant.organizationId(),
            tenant.employeeId(), recordId, hash, sourceUpdatedAt,
            """
            {
              "providerRecordId":"%s",
              "providerEmployeeId":"GHR-MAPPED",
              "employeeNumber":"%s",
              "workEmail":"mapping@example.test"
            }
            """.formatted(recordId, tenant.employeeNumber()));
        return id;
    }

    private UUID attendanceFact(
        UUID connectionId,
        UUID runId,
        TenantEmployee tenant,
        String providerEmployeeId,
        String recordId,
        String hash
    ) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO greythr_imported_facts(
                id, connection_id, sync_run_id, organization_id, employee_id,
                provider_employee_id, fact_kind, work_date,
                provider_record_id, payload_hash, source_updated_at, payload
            ) VALUES (?, ?, ?, ?, ?, ?, 'ATTENDANCE', CURRENT_DATE, ?, ?,
                      CURRENT_TIMESTAMP, ?::jsonb)
            """, id, connectionId, runId, tenant.organizationId(),
            tenant.employeeId(), providerEmployeeId, recordId, hash,
            attendancePayload(providerEmployeeId, recordId));
        return id;
    }

    private String attendancePayload(
        String providerEmployeeId,
        String recordId
    ) {
        LocalDate today = LocalDate.now();
        return """
            {
              "providerRecordId":"%s",
              "providerEmployeeId":"%s",
              "workDate":"%s",
              "checkInAt":"%sT09:00:00Z",
              "checkOutAt":"%sT17:00:00Z"
            }
            """.formatted(
            recordId, providerEmployeeId, today, today, today);
    }

    private List<TenantEmployee> tenantEmployees() {
        return jdbc.query("""
            SELECT employee.id, employee.organization_id,
                   employee.employee_number
            FROM employees employee
            ORDER BY employee.organization_id, employee.id
            """, (result, row) -> new TenantEmployee(
                result.getObject("id", UUID.class),
                result.getObject("organization_id", UUID.class),
                result.getString("employee_number")));
    }

    private TenantEmployee isolatedTenantEmployee() {
        UUID organizationId = UUID.randomUUID();
        String suffix = organizationId.toString().substring(0, 8);
        jdbc.update("""
            INSERT INTO organizations(
                id, code, legal_name, display_name, organization_type, status
            ) VALUES (?, ?, ?, ?, 'OTHER', 'ACTIVE')
            """, organizationId, "SCHEMA-" + suffix,
            "Schema Isolation " + suffix, "Schema Isolation " + suffix);
        UUID employeeId = UUID.randomUUID();
        String employeeNumber = "SCHEMA-" + suffix;
        jdbc.update("""
            INSERT INTO employees(
                id, organization_id, employee_number, work_email, join_date,
                created_by_subject
            ) VALUES (?, ?, ?, ?, CURRENT_DATE, 'schema-test')
            """, employeeId, organizationId, employeeNumber,
            suffix + "@schema-isolation.example.test");
        return new TenantEmployee(
            employeeId, organizationId, employeeNumber);
    }

    private record TenantEmployee(
        UUID employeeId,
        UUID organizationId,
        String employeeNumber
    ) {
    }

    private record Mutation(String table, UUID id) {
    }
}
