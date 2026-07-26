package com.vms.workflow.application;

import org.springframework.stereotype.Component;

@Component
public class ProviderNeutralF05CertificationReadinessPublisher
    implements F05CertificationReadinessPublisher {
    private final CertificationConfiguration configuration;

    public ProviderNeutralF05CertificationReadinessPublisher(
        CertificationConfiguration configuration
    ) {
        this.configuration = configuration;
    }

    @Override
    public String configurationStatus() {
        return configuration.f05HandoffStatus();
    }

    @Override
    public PublishResult publish(ReadinessFact fact) {
        return new PublishResult(
            "NOT_CONFIGURED",
            "certification.confirmation.readiness.v1",
            "F05_CONSUMER_NOT_CONFIGURED",
            false);
    }
}
