package com.vms.workflow.application;

import com.vms.workflow.api.CertificationDtos.InboundMessageRecordInput;
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
import java.util.HexFormat;
import java.util.UUID;

@Component
public class InboundMessageAuthenticator {
    private static final String ALGORITHM = "HmacSHA256";

    private final Clock clock;
    private final Duration replayWindow;
    private final byte[] secret;

    public InboundMessageAuthenticator(
        Clock clock,
        @Value("${vms.certification.inbound-signing-secret:}")
        String encodedSecret,
        @Value("${vms.certification.inbound-replay-window:PT5M}")
        Duration replayWindow
    ) {
        this.clock = clock;
        this.replayWindow = replayWindow;
        if (replayWindow.isNegative()
            || replayWindow.isZero()
            || replayWindow.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException(
                "Inbound replay window must be between zero and one hour.");
        }
        this.secret = decodeSecret(encodedSecret);
    }

    public boolean configured() {
        return secret != null;
    }

    public boolean verify(
        UUID monthId,
        long epochSeconds,
        String suppliedSignature,
        InboundMessageRecordInput input
    ) {
        if (secret == null
            || suppliedSignature == null
            || suppliedSignature.length() > 128) {
            return false;
        }
        Instant supplied;
        try {
            supplied = Instant.ofEpochSecond(epochSeconds);
        } catch (RuntimeException exception) {
            return false;
        }
        if (Duration.between(supplied, clock.instant()).abs()
            .compareTo(replayWindow) > 0) {
            return false;
        }
        byte[] expected = hmac(payload(monthId, epochSeconds, input));
        byte[] actual;
        try {
            String normalized = suppliedSignature.startsWith("v1=")
                ? suppliedSignature.substring(3) : suppliedSignature;
            actual = HexFormat.of().parseHex(normalized);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        return MessageDigest.isEqual(expected, actual);
    }

    public String sign(
        UUID monthId,
        long epochSeconds,
        InboundMessageRecordInput input
    ) {
        if (secret == null) {
            throw new IllegalStateException(
                "Inbound authentication is not configured.");
        }
        return "v1=" + HexFormat.of().formatHex(
            hmac(payload(monthId, epochSeconds, input)));
    }

    private String payload(
        UUID monthId,
        long epochSeconds,
        InboundMessageRecordInput input
    ) {
        return String.join("\n",
            "f04-inbound-signature-v1",
            Long.toString(epochSeconds),
            monthId.toString(),
            nullable(input.requestId()),
            input.providerMessageFingerprint(),
            input.senderAddress().strip().toLowerCase(java.util.Locale.ROOT),
            nullable(input.rawSha256()),
            input.classifiedIntent(),
            input.providerReceivedAt().toInstant().toString());
    }

    private byte[] hmac(String value) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                "Inbound HMAC authentication is unavailable.", exception);
        }
    }

    private static byte[] decodeSecret(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        byte[] value;
        try {
            value = Base64.getDecoder().decode(encoded.strip());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "Inbound signing secret must be Base64.", exception);
        }
        if (value.length < 32) {
            throw new IllegalArgumentException(
                "Inbound signing secret must contain at least 256 bits.");
        }
        return value;
    }

    private static String nullable(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
