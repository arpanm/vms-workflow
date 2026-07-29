package com.vms.workflow.infrastructure;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component("workflowReadiness")
public final class WorkflowReadinessHealthIndicator
    implements HealthIndicator {
    private final JdbcTemplate jdbc;

    public WorkflowReadinessHealthIndicator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Health health() {
        try {
            Boolean ready = jdbc.query("""
                SELECT to_regclass('public.flyway_schema_history') IS NOT NULL
                   AND to_regclass('public.f07_feature_flags') IS NOT NULL
                """, statement -> statement.setQueryTimeout(1), result ->
                    result.next() && result.getBoolean(1));
            return Boolean.TRUE.equals(ready)
                ? Health.up().withDetail("code", "READY").build()
                : Health.down().withDetail(
                    "code", "REQUIRED_SCHEMA_UNAVAILABLE").build();
        } catch (DataAccessException exception) {
            return Health.down()
                .withDetail("code", "MANDATORY_DATABASE_UNAVAILABLE")
                .build();
        }
    }
}
