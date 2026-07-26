package com.vms.workflow.application;

import org.springframework.stereotype.Component;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class ConfirmationTokenCodec {
    public static final String ALGORITHM = "PBKDF2-HMAC-SHA256";
    private static final String JCA_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int DERIVED_BITS = 256;

    private final SecureRandom secureRandom = new SecureRandom();
    private final CertificationConfiguration configuration;

    public ConfirmationTokenCodec(CertificationConfiguration configuration) {
        this.configuration = configuration;
    }

    public IssuedToken issue() {
        byte[] tokenBytes = new byte[32];
        byte[] salt = new byte[24];
        secureRandom.nextBytes(tokenBytes);
        secureRandom.nextBytes(salt);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        String encodedSalt = Base64.getEncoder().encodeToString(salt);
        return new IssuedToken(
            token,
            derive(token, salt, configuration.tokenWorkFactor()),
            encodedSalt,
            ALGORITHM,
            configuration.tokenWorkFactor());
    }

    public boolean matches(
        String candidate,
        String encodedHash,
        String encodedSalt,
        int workFactor
    ) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        byte[] expected;
        byte[] actual;
        try {
            expected = Base64.getDecoder().decode(encodedHash);
            actual = Base64.getDecoder().decode(
                derive(candidate, Base64.getDecoder().decode(encodedSalt), workFactor));
        } catch (IllegalArgumentException exception) {
            return false;
        }
        return MessageDigest.isEqual(expected, actual);
    }

    private String derive(String token, byte[] salt, int workFactor) {
        PBEKeySpec spec = new PBEKeySpec(
            token.toCharArray(), salt, workFactor, DERIVED_BITS);
        try {
            byte[] encoded = SecretKeyFactory.getInstance(JCA_ALGORITHM)
                .generateSecret(spec)
                .getEncoded();
            return Base64.getEncoder().encodeToString(encoded);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                "Secure confirmation token hashing is unavailable.", exception);
        } finally {
            spec.clearPassword();
        }
    }

    public record IssuedToken(
        String plaintext,
        String encodedHash,
        String encodedSalt,
        String algorithm,
        int workFactor
    ) {
    }
}
