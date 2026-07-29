package com.vms.workflow.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;

@Component
public record DeliveryCommitmentConfiguration(
    String providerStatus,
    Duration retryDelay
) {
    public DeliveryCommitmentConfiguration(
        @Value("${vms.delivery.commitment.provider-status:NOT_CONFIGURED}")
        String providerStatus,
        @Value("${vms.delivery.commitment.retry-delay:PT5S}")
        Duration retryDelay
    ) {
        String normalized = providerStatus == null
            ? "NOT_CONFIGURED" : providerStatus.strip();
        if (!Set.of("NOT_CONFIGURED", "ACTION_REQUIRED", "CONFIGURED")
            .contains(normalized)) {
            throw new IllegalArgumentException(
                "Delivery commitment provider status is invalid.");
        }
        if (retryDelay.isNegative() || retryDelay.isZero()
            || retryDelay.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException(
                "Delivery commitment retry delay must be between zero and one hour.");
        }
        this.providerStatus = normalized;
        this.retryDelay = retryDelay;
    }
}
