package com.vms.workflow.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public record LinearReconciliationConfiguration(
    boolean workerEnabled,
    int pageSize,
    int maxPagesPerRun,
    int maxAttempts,
    Duration successInterval,
    Duration retryDelay
) {
    public LinearReconciliationConfiguration(
        @Value("${vms.linear.reconciliation.worker-enabled:false}")
        boolean workerEnabled,
        @Value("${vms.linear.reconciliation.page-size:100}") int pageSize,
        @Value("${vms.linear.reconciliation.max-pages-per-run:10}")
        int maxPagesPerRun,
        @Value("${vms.linear.reconciliation.max-attempts:5}") int maxAttempts,
        @Value("${vms.linear.reconciliation.success-interval:PT15M}")
        Duration successInterval,
        @Value("${vms.linear.reconciliation.retry-delay:PT1M}")
        Duration retryDelay
    ) {
        if (pageSize < 1 || pageSize > 250) {
            throw new IllegalArgumentException(
                "Linear reconciliation page size must be between 1 and 250.");
        }
        if (maxPagesPerRun < 1 || maxPagesPerRun > 100) {
            throw new IllegalArgumentException(
                "Linear reconciliation max pages must be between 1 and 100.");
        }
        if (maxAttempts < 1 || maxAttempts > 20) {
            throw new IllegalArgumentException(
                "Linear reconciliation max attempts must be between 1 and 20.");
        }
        if (successInterval.isNegative() || successInterval.isZero()
            || retryDelay.isNegative() || retryDelay.isZero()) {
            throw new IllegalArgumentException(
                "Linear reconciliation intervals must be positive.");
        }
        this.workerEnabled = workerEnabled;
        this.pageSize = pageSize;
        this.maxPagesPerRun = maxPagesPerRun;
        this.maxAttempts = maxAttempts;
        this.successInterval = successInterval;
        this.retryDelay = retryDelay;
    }
}
