package com.vms.workflow.application;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("system-e2e & !prod")
public class RecordedDeliveryCommitmentEmailAdapter
    implements DeliveryCommitmentEmailAdapter {
    private final DeliveryCommitmentConfiguration configuration;

    public RecordedDeliveryCommitmentEmailAdapter(
        DeliveryCommitmentConfiguration configuration
    ) {
        this.configuration = configuration;
    }

    @Override
    public String configurationStatus() {
        return configuration.providerStatus();
    }

    @Override
    public SendResult send(OutboundCommitment commitment) {
        if (!"CONFIGURED".equals(configuration.providerStatus())) {
            return new SendResult(
                "NOT_CONFIGURED", null, null,
                "COMMITMENT_PROVIDER_NOT_CONFIGURED", false);
        }
        return new SendResult(
            "SENT",
            "recorded-commitment-" + commitment.outboxId(),
            "recorded-commitment-thread-" + commitment.baselineId(),
            null,
            false);
    }
}
