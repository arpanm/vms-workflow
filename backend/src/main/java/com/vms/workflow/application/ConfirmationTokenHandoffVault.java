package com.vms.workflow.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Component
public class ConfirmationTokenHandoffVault {
    private static final int NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final JdbcTemplate jdbc;
    private final CertificationConfiguration configuration;
    private final SecureRandom secureRandom = new SecureRandom();
    private final SecretKeySpec key;

    public ConfirmationTokenHandoffVault(
        JdbcTemplate jdbc,
        CertificationConfiguration configuration
    ) {
        this.jdbc = jdbc;
        this.configuration = configuration;
        this.key = configuredKey(configuration);
    }

    boolean enabled() {
        return key != null;
    }

    @Transactional
    public void store(
        UUID tokenId,
        UUID requestId,
        UUID outboxId,
        String plaintextToken
    ) {
        if (key == null) {
            throw new IllegalStateException(
                "Secure token handoff is not configured.");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        byte[] ciphertext = encrypt(
            plaintextToken, nonce, aad(tokenId, requestId, outboxId));
        jdbc.update("""
            INSERT INTO confirmation_token_handoffs
                (id, token_id, request_id, outbox_id, encrypted_token,
                 nonce, key_version, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING')
            """, UUID.randomUUID(), tokenId, requestId, outboxId,
            ciphertext, nonce, configuration.tokenHandoffKeyVersion());
    }

    @Transactional(readOnly = true)
    public List<CertificationEmailAdapter.SecureActionLink> linksForOutbox(
        UUID outboxId
    ) {
        List<HandoffRow> rows = jdbc.query("""
            SELECT handoff.token_id, handoff.request_id, handoff.outbox_id,
                   handoff.encrypted_token, handoff.nonce,
                   token.expires_at
            FROM confirmation_token_handoffs handoff
            JOIN confirmation_secure_tokens token
              ON token.id = handoff.token_id
             AND token.request_id = handoff.request_id
            JOIN business_confirmation_requests request
              ON request.id = handoff.request_id
            WHERE handoff.outbox_id = ?
              AND handoff.status = 'PENDING'
              AND token.consumed_at IS NULL
              AND token.expires_at > CURRENT_TIMESTAMP
              AND request.status = 'AWAITING_RESPONSE'
              AND NOT EXISTS (
                  SELECT 1
                  FROM confirmation_token_revocations revocation
                  WHERE revocation.token_id = token.id
              )
            """, (rs, rowNum) -> new HandoffRow(
                rs.getObject("token_id", UUID.class),
                rs.getObject("request_id", UUID.class),
                rs.getObject("outbox_id", UUID.class),
                rs.getBytes("encrypted_token"),
                rs.getBytes("nonce"),
                rs.getObject("expires_at", OffsetDateTime.class)),
            outboxId);
        return rows.stream()
            .map(row -> new CertificationEmailAdapter.SecureActionLink(
                row.requestId(), row.tokenId(),
                decrypt(
                    row.ciphertext(), row.nonce(),
                    aad(row.tokenId(), row.requestId(), row.outboxId())),
                row.expiresAt()))
            .toList();
    }

    @Transactional
    public void delivered(UUID outboxId) {
        jdbc.update("""
            UPDATE confirmation_token_handoffs
            SET status = 'DELIVERED', delivered_at = CURRENT_TIMESTAMP,
                failure_code = NULL
            WHERE outbox_id = ? AND status = 'PENDING'
            """, outboxId);
    }

    @Transactional
    public void failed(UUID outboxId, String failureCode) {
        jdbc.update("""
            UPDATE confirmation_token_handoffs
            SET status = 'FAILED', failure_code = ?
            WHERE outbox_id = ? AND status = 'PENDING'
            """, failureCode, outboxId);
    }

    @Transactional
    public void requeue(UUID outboxId) {
        jdbc.update("""
            UPDATE confirmation_token_handoffs
            SET status = 'PENDING', failure_code = NULL
            WHERE outbox_id = ? AND status = 'FAILED'
            """, outboxId);
    }

    @Transactional
    public void revokeRequest(UUID requestId, String reasonCode) {
        jdbc.update("""
            UPDATE confirmation_token_handoffs
            SET status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP,
                failure_code = ?
            WHERE request_id = ? AND status = 'PENDING'
            """, reasonCode, requestId);
    }

    @Transactional
    public void revokeToken(UUID tokenId, String reasonCode) {
        jdbc.update("""
            UPDATE confirmation_token_handoffs
            SET status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP,
                failure_code = ?
            WHERE token_id = ? AND status = 'PENDING'
            """, reasonCode, tokenId);
    }

    private byte[] encrypt(String plaintext, byte[] nonce, byte[] aad) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                Cipher.ENCRYPT_MODE, key,
                new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(
                plaintext.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                "Secure confirmation token encryption is unavailable.",
                exception);
        }
    }

    private String decrypt(byte[] ciphertext, byte[] nonce, byte[] aad) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                Cipher.DECRYPT_MODE, key,
                new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(aad);
            return new String(
                cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                "Secure confirmation token handoff cannot be decrypted.",
                exception);
        }
    }

    private byte[] aad(UUID tokenId, UUID requestId, UUID outboxId) {
        return (
            configuration.tokenHandoffKeyVersion() + ":" + tokenId + ":"
                + requestId + ":" + outboxId)
            .getBytes(StandardCharsets.UTF_8);
    }

    private static SecretKeySpec configuredKey(
        CertificationConfiguration configuration
    ) {
        if (configuration.tokenHandoffKey().isBlank()) {
            if ("CONFIGURED".equals(configuration.emailProviderStatus())) {
                throw new IllegalStateException(
                    "A 256-bit token handoff key is required when email delivery is configured.");
            }
            return null;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(
                configuration.tokenHandoffKey());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "Certification token handoff key must be Base64.", exception);
        }
        if (decoded.length != 32) {
            throw new IllegalArgumentException(
                "Certification token handoff key must decode to 32 bytes.");
        }
        return new SecretKeySpec(decoded, "AES");
    }

    private record HandoffRow(
        UUID tokenId,
        UUID requestId,
        UUID outboxId,
        byte[] ciphertext,
        byte[] nonce,
        OffsetDateTime expiresAt
    ) {
    }
}
