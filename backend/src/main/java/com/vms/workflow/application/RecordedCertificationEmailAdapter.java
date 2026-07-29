package com.vms.workflow.application;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("system-e2e & !prod")
public class RecordedCertificationEmailAdapter
    implements CertificationEmailAdapter {
    private final CertificationConfiguration configuration;

    public RecordedCertificationEmailAdapter(
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
        if (!"CONFIGURED".equals(configuration.emailProviderStatus())) {
            return new SendResult(
                "NOT_CONFIGURED", null, null,
                "EMAIL_PROVIDER_NOT_CONFIGURED", false);
        }
        String[] idempotencyParts = message.idempotencyKey().split(":", 3);
        String threadKey = idempotencyParts.length == 3
            ? idempotencyParts[1] : message.outboxId().toString();
        return new SendResult(
            "SENT",
            "recorded-certification-" + message.outboxId(),
            "recorded-certification-thread-" + threadKey,
            null,
            false);
    }
}
