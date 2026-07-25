package com.vms.workflow.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtDecoderIT {
    private static final String ISSUER = "https://issuer.example.test";
    private static final String AUDIENCE = "vms-api";

    private static HttpServer jwksServer;
    private static RSAKey trustedKey;
    private static String jwksUri;
    private static JwtDecoder decoder;

    @BeforeAll
    static void startJwks() throws Exception {
        trustedKey = new RSAKeyGenerator(2048).keyID("trusted-key").generate();
        byte[] jwks = new JWKSet(trustedKey.toPublicJWK())
            .toString()
            .getBytes(StandardCharsets.UTF_8);
        jwksServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwksServer.createContext("/jwks", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jwks.length);
            exchange.getResponseBody().write(jwks);
            exchange.close();
        });
        jwksServer.start();
        jwksUri = "http://127.0.0.1:" + jwksServer.getAddress().getPort() + "/jwks";
        decoder = new SecurityConfig().jwtDecoder(jwksUri, AUDIENCE, ISSUER);
    }

    @AfterAll
    static void stopJwks() {
        jwksServer.stop(0);
    }

    @Test
    void acceptsValidRs256TokenWithExactIssuerAudienceAndTime() throws Exception {
        Jwt jwt = decoder.decode(rs256Token(trustedKey, ISSUER, AUDIENCE,
            Instant.now().minusSeconds(5), Instant.now().plusSeconds(300), null));
        assertEquals("real-decoder-user", jwt.getSubject());
        assertEquals(ISSUER, jwt.getIssuer().toString());
    }

    @Test
    void rejectsWrongIssuerAudienceKeyAndTimeClaims() throws Exception {
        Instant now = Instant.now();
        RSAKey untrustedKey = new RSAKeyGenerator(2048).keyID("untrusted-key").generate();

        assertRejected(rs256Token(trustedKey, "https://other-issuer.example", AUDIENCE,
            now.minusSeconds(5), now.plusSeconds(300), null));
        assertRejected(rs256Token(trustedKey, ISSUER, "other-api",
            now.minusSeconds(5), now.plusSeconds(300), null));
        assertRejected(rs256Token(untrustedKey, ISSUER, AUDIENCE,
            now.minusSeconds(5), now.plusSeconds(300), null));
        assertRejected(rs256Token(trustedKey, ISSUER, AUDIENCE,
            now.minusSeconds(600), now.minusSeconds(120), null));
        assertRejected(rs256Token(trustedKey, ISSUER, AUDIENCE,
            now, now.plusSeconds(600), now.plusSeconds(300)));
    }

    @Test
    void rejectsNonRs256Algorithm() throws Exception {
        byte[] secret = "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8);
        JWTClaimsSet claims = claims(ISSUER, AUDIENCE, Instant.now().minusSeconds(5),
            Instant.now().plusSeconds(300), null);
        SignedJWT token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        token.sign(new MACSigner(secret));
        assertRejected(token.serialize());
    }

    private static String rs256Token(RSAKey key, String issuer, String audience,
                                     Instant issuedAt, Instant expiresAt, Instant notBefore) throws Exception {
        SignedJWT token = new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
            claims(issuer, audience, issuedAt, expiresAt, notBefore));
        token.sign(new RSASSASigner(key));
        return token.serialize();
    }

    private static JWTClaimsSet claims(String issuer, String audience,
                                       Instant issuedAt, Instant expiresAt, Instant notBefore) {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
            .subject("real-decoder-user")
            .issuer(issuer)
            .audience(List.of(audience))
            .issueTime(Date.from(issuedAt))
            .expirationTime(Date.from(expiresAt));
        if (notBefore != null) {
            builder.notBeforeTime(Date.from(notBefore));
        }
        return builder.build();
    }

    private static void assertRejected(String token) {
        assertThrows(JwtException.class, () -> decoder.decode(token));
    }
}
