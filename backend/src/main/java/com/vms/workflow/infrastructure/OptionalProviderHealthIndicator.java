package com.vms.workflow.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Optional provider capability is visible as degradation but is deliberately
 * excluded from the mandatory readiness group.
 */
@Component("optionalProviders")
public class OptionalProviderHealthIndicator implements HealthIndicator {
    private static final String VERIFIED = "VERIFIED";

    private final List<String> capabilityStates;

    public OptionalProviderHealthIndicator(
        @Value("${vms.certification.email-provider-status:NOT_CONFIGURED}")
        String email,
        @Value("${vms.certification.object-storage-provider-status:NOT_CONFIGURED}")
        String objectStorage,
        @Value("${vms.certification.f05-handoff-status:NOT_CONFIGURED}")
        String financeHandoff
    ) {
        this.capabilityStates = List.of(email, objectStorage, financeHandoff);
    }

    @Override
    public Health health() {
        long unavailable = capabilityStates.stream()
            .filter(value -> !VERIFIED.equalsIgnoreCase(value))
            .count();
        if (unavailable == 0) {
            return Health.up()
                .withDetail("summary", "optional providers verified")
                .build();
        }
        return Health.status("DEGRADED")
            .withDetail("summary", "optional provider action required")
            .withDetail("unverifiedCapabilityCount", unavailable)
            .build();
    }
}
