package com.vms.workflow.security;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OutboundUriPolicyTest {
    private static final Set<String> LINEAR = Set.of("linear.app");

    @Test
    void acceptsOnlyExactHttpsProviderHost() {
        assertEquals(
            URI.create("https://linear.app/acme/issue/ABC-1"),
            OutboundUriPolicy.requireHttpsHost(
                "https://linear.app/acme/issue/ABC-1", LINEAR));
    }

    @Test
    void rejectsSsrfAndCredentialForwardingShapes() {
        for (String value : new String[] {
            "http://linear.app/acme/issue/ABC-1",
            "https://linear.app.evil.example/acme/issue/ABC-1",
            "https://evil.linear.app/acme/issue/ABC-1",
            "https://user:secret@linear.app/acme/issue/ABC-1",
            "https://linear.app:8443/acme/issue/ABC-1",
            "https://127.0.0.1/latest/meta-data",
            "https://169.254.169.254/latest/meta-data",
            "//linear.app/acme/issue/ABC-1",
            "https://linear.app/acme/issue/ABC-1#token"
        }) {
            assertThrows(
                IllegalArgumentException.class,
                () -> OutboundUriPolicy.requireHttpsHost(value, LINEAR),
                value);
        }
    }
}
