package com.vms.workflow.application;

import org.springframework.stereotype.Component;

@Component
public class ProviderNeutralCertificationEmailAdapter
    implements CertificationEmailAdapter {
    private final CertificationConfiguration configuration;

    public ProviderNeutralCertificationEmailAdapter(
        CertificationConfiguration configuration
    ) {
        this.configuration = configuration;
    }

    @Override
    public String configurationStatus() {
        return configuration.emailProviderStatus();
    }

    @Override
    public SendResult send(OutboundMessage message) {
        return new SendResult(
            "NOT_CONFIGURED", null, null, "EMAIL_PROVIDER_NOT_CONFIGURED", false);
    }
}
