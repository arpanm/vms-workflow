package com.vms.workflow.application;

import java.util.UUID;

public interface DeliveryCommitmentEmailAdapter {
    String configurationStatus();

    SendResult send(OutboundCommitment commitment);

    record OutboundCommitment(
        UUID outboxId,
        UUID planVersionId,
        UUID baselineId,
        String idempotencyKey,
        String recipientSnapshotJson,
        String subject,
        String plainText,
        String htmlText,
        String archiveReference
    ) {
    }

    record SendResult(
        String status,
        String providerMessageId,
        String providerThreadId,
        String errorCode,
        boolean retryable
    ) {
    }
}
