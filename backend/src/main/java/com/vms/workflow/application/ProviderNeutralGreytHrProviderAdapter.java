package com.vms.workflow.application;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.UUID;

@Component
@Profile("!system-e2e | prod")
public class ProviderNeutralGreytHrProviderAdapter
    implements GreytHrProviderAdapter {

    @Override
    public CapabilityProbeResult probe(UUID connectionId) {
        return CapabilityProbeResult.failure(
            "PROVIDER_NOT_CONFIGURED", "PROVIDER_NEUTRAL");
    }

    @Override
    public FetchResult fetch(
        UUID connectionId,
        LocalDate dateFrom,
        LocalDate dateTo
    ) {
        return FetchResult.failure("PROVIDER_NOT_CONFIGURED");
    }
}
