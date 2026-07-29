package com.vms.workflow.infrastructure;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "spring.datasource.url="
        + "jdbc:tc:vmspostgresql:18-alpine:///vms_workflow_operational_metrics",
    "spring.datasource.driver-class-name="
        + "org.testcontainers.jdbc.ContainerDatabaseDriver",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.flyway.locations=classpath:db/migration,classpath:db/testdata",
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri="
        + "http://127.0.0.1:9/test-jwks",
    "vms.security.issuer=https://issuer.example.test",
    "vms.security.audience=vms-api"
})
@Transactional
class OperationalReadinessMetricsIT {
    private static final UUID ORGANIZATION =
        UUID.fromString("00000000-0000-0000-0000-000000000101");

    @Autowired
    private OperationalReadinessMetrics metrics;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void databaseRowsDriveFreshStaleAndDegradedGreytHrGauges() {
        UUID certification = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO integration_capability_certifications
                (id, organization_id, provider, status, certified_at,
                 capability_manifest)
            VALUES (?, ?, 'GREYTHR', 'CERTIFIED', CURRENT_TIMESTAMP,
                    '{"schema":"operational-metrics-it"}'::jsonb)
            """, certification, ORGANIZATION);

        UUID fresh = insertConnection(
            certification, "Metrics fresh ACTIVE", "ACTIVE",
            "CURRENT_TIMESTAMP - INTERVAL '15 seconds'", null);
        assertThat(metrics.greytHrFreshnessAge())
            .isBetween(0.0, 120.0);
        assertThat(metrics.greytHrDegradedConnections()).isZero();
        assertThat(gauge(
            "vms.operational.greythr.freshness.age.seconds"))
            .isBetween(0.0, 120.0);

        UUID stale = insertConnection(
            certification, "Metrics stale ACTIVE", "ACTIVE",
            "CURRENT_TIMESTAMP - INTERVAL '3 hours'", null);
        assertThat(metrics.greytHrFreshnessAge())
            .isBetween(10_700.0, 11_100.0);
        assertThat(metrics.greytHrDegradedConnections()).isZero();

        UUID degraded = insertConnection(
            certification, "Metrics DEGRADED", "DEGRADED",
            "CURRENT_TIMESTAMP - INTERVAL '4 hours'",
            "PROVIDER_UNAVAILABLE");
        assertThat(metrics.greytHrFreshnessAge())
            .isBetween(14_300.0, 14_700.0);
        assertThat(metrics.greytHrDegradedConnections()).isEqualTo(1);
        assertThat(gauge(
            "vms.operational.greythr.freshness.age.seconds"))
            .isBetween(14_300.0, 14_700.0);
        assertThat(gauge(
            "vms.operational.greythr.degraded.connections"))
            .isEqualTo(1);

        jdbc.update("""
            UPDATE greythr_connections
            SET status = 'DISABLED'
            WHERE id IN (?, ?, ?)
            """, fresh, stale, degraded);
        assertThat(metrics.greytHrFreshnessAge()).isZero();
        assertThat(metrics.greytHrDegradedConnections()).isZero();
    }

    private UUID insertConnection(
        UUID certification,
        String displayName,
        String status,
        String lastSuccessSql,
        String errorCode
    ) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO greythr_connections
                (id, organization_id, display_name, status, adapter_mode,
                 capability_certification_id, last_attempt_at,
                 last_success_at, last_error_code, created_by_subject)
            VALUES (?, ?, ?, ?, 'RECORDED_FIXTURE', ?,
                    CURRENT_TIMESTAMP, %s, ?, 'SYSTEM:METRICS_IT')
            """.formatted(lastSuccessSql),
            id, ORGANIZATION, displayName, status, certification, errorCode);
        return id;
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }
}
