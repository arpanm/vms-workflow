package com.vms.workflow.application;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!system-e2e")
public class ProviderNeutralDeliveryCommitmentEmailAdapter
    implements DeliveryCommitmentEmailAdapter {
    private final DeliveryCommitmentConfiguration configuration;

    public ProviderNeutralDeliveryCommitmentEmailAdapter(
        DeliveryCommitmentConfiguration configuration
    ) {
        this.configuration = configuration;
    }

    @Override
    public String configurationStatus() {
        return "ACTION_REQUIRED".equals(configuration.providerStatus())
            ? "ACTION_REQUIRED" : "NOT_CONFIGURED";
    }

    @Override
    public SendResult send(OutboundCommitment commitment) {
        return new SendResult(
            "NOT_CONFIGURED", null, null,
            "COMMITMENT_PROVIDER_NOT_CONFIGURED", false);
    }
}
