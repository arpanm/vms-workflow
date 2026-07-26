package com.vms.workflow.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface CertificationEmailAdapter {
    String configurationStatus();

    SendResult send(OutboundMessage message);

    record OutboundMessage(
        UUID outboxId,
        String idempotencyKey,
        String subject,
        String plainText,
        String htmlText,
        String recipientSnapshotJson,
        UUID correlationId,
        List<SecureActionLink> secureActionLinks
    ) {
    }

    record SecureActionLink(
        UUID requestId,
        UUID tokenId,
        String plaintextToken,
        OffsetDateTime expiresAt
    ) {
    }

    record SendResult(
        String status,
        String providerMessageId,
        String providerThreadId,
        String errorCategory,
        boolean retryable
    ) {
    }
}
