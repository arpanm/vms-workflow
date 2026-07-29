package com.vms.workflow.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OptionalProviderHealthIndicatorTest {
    @Test
    void reportsOptionalCapabilityDegradationWithoutNamesOrEndpoints() {
        var health = new OptionalProviderHealthIndicator(
            "NOT_CONFIGURED", "VERIFIED", "CONFIGURED_UNVERIFIED").health();

        assertEquals(new Status("DEGRADED"), health.getStatus());
        assertEquals(2L, health.getDetails().get("unverifiedCapabilityCount"));
    }

    @Test
    void reportsUpOnlyWhenAllCapabilitiesAreVerified() {
        var health = new OptionalProviderHealthIndicator(
            "VERIFIED", "verified", "VERIFIED").health();

        assertEquals(Status.UP, health.getStatus());
    }
}
