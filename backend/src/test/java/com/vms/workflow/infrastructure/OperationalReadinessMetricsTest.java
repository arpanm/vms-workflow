package com.vms.workflow.infrastructure;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OperationalReadinessMetricsTest {

    @Test
    void databaseFailureIsUnknownAndIncrementsTheErrorCounter() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(jdbc.query(anyString(),
            any(org.springframework.jdbc.core.PreparedStatementSetter.class),
            any(org.springframework.jdbc.core.ResultSetExtractor.class)))
            .thenThrow(new QueryTimeoutException("bounded test timeout"));
        OperationalReadinessMetrics metrics =
            new OperationalReadinessMetrics(jdbc, registry);

        assertThat(metrics.pendingJobs()).isNaN();
        assertThat(registry.counter("vms.operational.scrape.errors").count())
            .isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void successfulScalarRemainsNonNegative() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(jdbc.query(anyString(),
            any(org.springframework.jdbc.core.PreparedStatementSetter.class),
            any(org.springframework.jdbc.core.ResultSetExtractor.class)))
            .thenReturn(BigDecimal.valueOf(3));
        OperationalReadinessMetrics metrics =
            new OperationalReadinessMetrics(jdbc, registry);

        assertThat(metrics.retentionActionRequired()).isEqualTo(3);
        assertThat(metrics.greytHrFreshnessAge()).isEqualTo(3);
        assertThat(metrics.greytHrDegradedConnections()).isEqualTo(3);
        assertThat(registry.find(
            "vms.operational.greythr.freshness.age.seconds").gauge())
            .isNotNull();
        assertThat(registry.find(
            "vms.operational.greythr.degraded.connections").gauge())
            .isNotNull();
        assertThat(registry.counter("vms.operational.scrape.errors").count())
            .isZero();
    }
}
