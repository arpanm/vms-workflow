package com.vms.workflow.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public record CertificationConfiguration(
    Duration tokenTtl,
    Duration defaultConfirmationDue,
    int tokenWorkFactor,
    String emailProviderStatus,
    String objectStorageProviderStatus,
    String f05HandoffStatus,
    String tokenHandoffKey,
    String tokenHandoffKeyVersion
) {
    public CertificationConfiguration(
        @Value("${vms.certification.token-ttl:PT72H}") Duration tokenTtl,
        @Value("${vms.certification.default-confirmation-due:PT120H}")
        Duration defaultConfirmationDue,
        @Value("${vms.certification.token-work-factor:120000}") int tokenWorkFactor,
        @Value("${vms.certification.email-provider-status:NOT_CONFIGURED}")
        String emailProviderStatus,
        @Value("${vms.certification.object-storage-provider-status:NOT_CONFIGURED}")
        String objectStorageProviderStatus,
        @Value("${vms.certification.f05-handoff-status:NOT_CONFIGURED}")
        String f05HandoffStatus,
        @Value("${vms.certification.token-handoff-key:}")
        String tokenHandoffKey,
        @Value("${vms.certification.token-handoff-key-version:local-v1}")
        String tokenHandoffKeyVersion
    ) {
        this.tokenTtl = tokenTtl;
        this.defaultConfirmationDue = defaultConfirmationDue;
        this.tokenWorkFactor = tokenWorkFactor;
        this.emailProviderStatus = externalStatus(emailProviderStatus);
        this.objectStorageProviderStatus = externalStatus(objectStorageProviderStatus);
        this.f05HandoffStatus = externalStatus(f05HandoffStatus);
        this.tokenHandoffKey = tokenHandoffKey == null
            ? "" : tokenHandoffKey.strip();
        this.tokenHandoffKeyVersion = tokenHandoffKeyVersion == null
            ? "" : tokenHandoffKeyVersion.strip();
        if (tokenTtl.compareTo(Duration.ofMinutes(5)) < 0
            || tokenTtl.compareTo(Duration.ofDays(30)) > 0) {
            throw new IllegalArgumentException(
                "Certification token TTL must be between five minutes and 30 days.");
        }
        if (defaultConfirmationDue.compareTo(Duration.ofHours(1)) < 0
            || defaultConfirmationDue.compareTo(Duration.ofDays(90)) > 0) {
            throw new IllegalArgumentException(
                "Confirmation due duration must be between one hour and 90 days.");
        }
        if (tokenWorkFactor < 100_000) {
            throw new IllegalArgumentException(
                "Confirmation token work factor must be at least 100000.");
        }
        if (this.tokenHandoffKeyVersion.isBlank()
            || this.tokenHandoffKeyVersion.length() > 80) {
            throw new IllegalArgumentException(
                "Certification token handoff key version is invalid.");
        }
    }

    private static String externalStatus(String value) {
        if (!java.util.Set.of(
                "NOT_CONFIGURED", "ACTION_REQUIRED", "CONFIGURED").contains(value)) {
            throw new IllegalArgumentException(
                "External certification capability status is invalid.");
        }
        return value;
    }
}
