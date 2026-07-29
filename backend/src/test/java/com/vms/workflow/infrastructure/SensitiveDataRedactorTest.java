package com.vms.workflow.infrastructure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Map;

class SensitiveDataRedactorTest {
    @Test
    void problemInstancePathRemainsAValidNonDisclosingUriPath() {
        String resourceId = "00000000-0000-4000-8000-000000000777";

        String path = SensitiveDataRedactor.problemInstancePath(
            "/api/v1/finance/invoices/" + resourceId);

        assertFalse(path.contains(resourceId));
        assertFalse(path.contains("["));
        assertFalse(path.contains("]"));
        assertTrue(path.endsWith("/redacted-id"));
    }

    @Test
    void redactsAuthorizationCookiesJwtPasswordsRawEmailObjectKeysAndRestrictedPii() {
        String jwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1c2VyLXNlY3JldCJ9.c2lnbmF0dXJl";
        String diagnostic = """
            Authorization: Bearer %s Cookie: SESSION=session-secret; csrf=csrf-secret
            password=hunter2 token=provider-token api_key=api-key-value
            sender=private.person@example.test raw/private-person/message.eml
            restricted/tenant/invoices/invoice.pdf
            user-private 00000000-0000-4000-8000-000000000777
            aadhaar=123412341234 bank_account=9988776655
            correlationId=correlation-safe errorCode=EXPORT_FAILED
            """.formatted(jwt);

        String redacted = SensitiveDataRedactor.diagnostic(diagnostic);

        for (String secret : new String[] {
            jwt, "session-secret", "csrf-secret", "hunter2", "provider-token",
            "api-key-value", "private.person@example.test",
            "raw/private-person/message.eml",
            "restricted/tenant/invoices/invoice.pdf", "user-private",
            "00000000-0000-4000-8000-000000000777", "123412341234",
            "9988776655"
        }) {
            assertFalse(redacted.contains(secret), secret);
        }
        assertTrue(redacted.contains("correlationId=correlation-safe"));
        assertTrue(redacted.contains("errorCode=EXPORT_FAILED"));
        assertTrue(redacted.length() <= 1_000);
    }

    @Test
    void structuredFactsRedactNestedSecretKeysAndDiagnosticValues() {
        Object redacted = SensitiveDataRedactor.structured(Map.of(
            "webhook_secret", "provider-secret",
            "nested", Map.of(
                "email", "private.person@example.test",
                "objectKey", "raw/private/message.eml")));
        String rendered = redacted.toString();

        assertFalse(rendered.contains("provider-secret"));
        assertFalse(rendered.contains("private.person@example.test"));
        assertFalse(rendered.contains("raw/private/message.eml"));
        assertTrue(rendered.contains("[redacted]"));
    }
}
