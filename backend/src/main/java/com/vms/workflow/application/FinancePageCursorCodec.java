package com.vms.workflow.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.UUID;

/**
 * Signed, opaque finance pagination cursors. A cursor is bound to the actor,
 * resource and current authorized engagement set, and expires quickly so it
 * cannot become a durable authorization capability.
 */
@Component
public class FinancePageCursorCodec {
    private static final Base64.Encoder ENCODER =
        Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] secret;
    private final Duration ttl;
    private final Clock clock;

    public FinancePageCursorCodec(
        @Value("${vms.finance.cursor-signing-secret}") String signingSecret,
        @Value("${vms.finance.cursor-ttl:PT30M}") Duration ttl,
        Clock clock
    ) {
        if (signingSecret == null || signingSecret.length() < 32) {
            throw new IllegalStateException(
                "VMS finance cursor signing secret must contain at least 32 characters.");
        }
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalStateException(
                "VMS finance cursor TTL must be positive.");
        }
        this.secret = signingSecret.getBytes(StandardCharsets.UTF_8);
        this.ttl = ttl;
        this.clock = clock;
    }

    public String encode(
        String resource,
        String subject,
        Collection<UUID> engagementScope,
        Instant snapshotAt,
        String lastSortValue,
        UUID lastId
    ) {
        String payload = String.join("\n",
            "v2",
            resource,
            hash(subject),
            scopeHash(engagementScope),
            snapshotAt.toString(),
            lastSortValue,
            lastId.toString());
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        return ENCODER.encodeToString(bytes) + "."
            + ENCODER.encodeToString(sign(bytes));
    }

    public Cursor decode(
        String encoded,
        String resource,
        String subject,
        Collection<UUID> engagementScope
    ) {
        try {
            String[] token = encoded == null ? new String[0]
                : encoded.split("\\.", -1);
            if (token.length != 2) {
                throw invalid();
            }
            byte[] payloadBytes = DECODER.decode(token[0]);
            byte[] suppliedSignature = DECODER.decode(token[1]);
            if (!ENCODER.encodeToString(payloadBytes).equals(token[0])
                || !ENCODER.encodeToString(suppliedSignature)
                    .equals(token[1])) {
                throw invalid();
            }
            if (!MessageDigest.isEqual(
                sign(payloadBytes), suppliedSignature)) {
                throw invalid();
            }
            String[] fields = new String(
                payloadBytes, StandardCharsets.UTF_8).split("\n", -1);
            if (fields.length != 7
                || (!"v1".equals(fields[0]) && !"v2".equals(fields[0]))
                || !resource.equals(fields[1])
                || !hash(subject).equals(fields[2])
                || !scopeHash(engagementScope).equals(fields[3])) {
                throw scopeMismatch();
            }
            Instant snapshotAt = "v1".equals(fields[0])
                ? Instant.ofEpochMilli(Long.parseLong(fields[4]))
                : Instant.parse(fields[4]);
            Instant now = clock.instant();
            if (snapshotAt.isAfter(now.plusSeconds(5))
                || snapshotAt.plus(ttl).isBefore(now)) {
                throw stale();
            }
            return new Cursor(
                snapshotAt, fields[5], UUID.fromString(fields[6]));
        } catch (IllegalArgumentException exception) {
            if (exception.getMessage() != null
                && exception.getMessage().startsWith(
                    "The pagination cursor")) {
                throw exception;
            }
            throw invalid();
        }
    }

    public Instant snapshot(String encoded, Cursor decoded) {
        return encoded == null || encoded.isBlank()
            ? clock.instant() : decoded.snapshotAt();
    }

    private String scopeHash(Collection<UUID> engagementScope) {
        return hash(engagementScope.stream()
            .sorted(Comparator.comparing(UUID::toString))
            .map(UUID::toString)
            .reduce((left, right) -> left + "," + right)
            .orElse(""));
    }

    private String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                "SHA-256 is unavailable.", exception);
        }
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                "Unable to sign finance pagination cursor.", exception);
        }
    }

    private IllegalArgumentException invalid() {
        return new IllegalArgumentException(
            "The pagination cursor is invalid.");
    }

    private IllegalArgumentException scopeMismatch() {
        return new IllegalArgumentException(
            "The pagination cursor is outside the authorized scope.");
    }

    private IllegalArgumentException stale() {
        return new IllegalArgumentException(
            "The pagination cursor is stale.");
    }

    public record Cursor(
        Instant snapshotAt,
        String lastSortValue,
        UUID lastId
    ) {
    }
}
