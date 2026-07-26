package com.vms.workflow.application;

import java.util.UUID;

public interface F05CertificationReadinessPublisher {
    String configurationStatus();

    PublishResult publish(ReadinessFact fact);

    record ReadinessFact(
        UUID engagementMonthId,
        UUID readinessRunId,
        String inputHash,
        UUID confirmationRequestId,
        String confirmationScopeHash,
        UUID correlationId
    ) {
    }

    record PublishResult(
        String status,
        String contractVersion,
        String failureCode,
        boolean retryable
    ) {
    }
}
