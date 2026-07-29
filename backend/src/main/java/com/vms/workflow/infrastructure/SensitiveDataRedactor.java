package com.vms.workflow.infrastructure;

import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded redaction for diagnostic text. Business data must be logged as
 * allowlisted structured fields; this is the final fail-closed guard for
 * exception text and request-path diagnostics.
 */
public final class SensitiveDataRedactor {
    private static final int MAX_DIAGNOSTIC_LENGTH = 1_000;
    private static final Pattern AUTHORIZATION = Pattern.compile(
        "(?i)\\bAuthorization\\s*[:=]\\s*(?:Bearer\\s+)?[^\\s,;]+");
    private static final Pattern COOKIE = Pattern.compile(
        "(?i)\\b(?:Cookie|Set-Cookie)\\s*[:=]\\s*[^\\r\\n]+");
    private static final Pattern SECRET_FIELD = Pattern.compile(
        "(?i)\"?\\b([a-z0-9_-]*(?:password|passwd|secret|token|api[_-]?key)"
            + "[a-z0-9_-]*)\"?\\s*[:=]\\s*"
            + "(?:\"[^\"]*\"|'[^']*'|[^\\s,;}&]+)");
    private static final Pattern JWT = Pattern.compile(
        "\\beyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\b");
    private static final Pattern EMAIL = Pattern.compile(
        "(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");
    private static final Pattern UUID = Pattern.compile(
        "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
            + "[0-9a-f]{4}-[0-9a-f]{12}\\b");
    private static final Pattern SUBJECT = Pattern.compile(
        "(?i)\\buser-[a-z0-9._-]+\\b");
    private static final Pattern OBJECT_KEY = Pattern.compile(
        "(?i)\\b(?:restricted|private|quarantine|raw)/"
            + "[A-Za-z0-9._~!$&'()*+,;=:@%/-]+");
    private static final Pattern RESTRICTED_PII = Pattern.compile(
        "(?i)\\b(?:pan|aadhaar|aadhar|bank[_ -]?account|tax[_ -]?id)"
            + "\\s*[:=]\\s*[^\\s,;}&]+");

    private SensitiveDataRedactor() {
    }

    public static String diagnostic(String value) {
        if (value == null || value.isBlank()) {
            return "redacted-diagnostic";
        }
        String redacted = AUTHORIZATION.matcher(value)
            .replaceAll("Authorization=[redacted]");
        redacted = COOKIE.matcher(redacted).replaceAll("Cookie=[redacted]");
        redacted = SECRET_FIELD.matcher(redacted)
            .replaceAll("$1=[redacted]");
        redacted = JWT.matcher(redacted).replaceAll("[redacted-jwt]");
        redacted = EMAIL.matcher(redacted).replaceAll("[redacted-email]");
        redacted = UUID.matcher(redacted).replaceAll("[redacted-id]");
        redacted = SUBJECT.matcher(redacted).replaceAll("[redacted-subject]");
        redacted = OBJECT_KEY.matcher(redacted).replaceAll("[redacted-object-key]");
        redacted = RESTRICTED_PII.matcher(redacted)
            .replaceAll("[redacted-pii]");
        return redacted.substring(0, Math.min(redacted.length(), MAX_DIAGNOSTIC_LENGTH));
    }

    /**
     * Produces a syntactically valid, non-disclosing URI path for RFC 9457
     * problem instances. UUID resource identifiers are request-controlled
     * data and must not be reflected in authorization or not-found bodies.
     */
    public static String problemInstancePath(String value) {
        return diagnostic(value)
            .replace("[redacted-id]", "redacted-id")
            .replace("[redacted-subject]", "redacted-subject")
            .replace("[redacted-object-key]", "redacted-object-key")
            .replace("[redacted-email]", "redacted-email")
            .replace("[redacted-pii]", "redacted-pii")
            .replace("[redacted-jwt]", "redacted-jwt");
    }

    /**
     * Redacts untrusted diagnostic facts while preserving their JSON shape.
     * Stable correlation/object identifiers belong in dedicated allowlisted
     * columns, not inside arbitrary diagnostic payloads.
     */
    public static Object structured(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String safeKey = diagnostic(String.valueOf(key));
                if (isSecretKey(String.valueOf(key))) {
                    result.put(safeKey, "[redacted]");
                } else {
                    result.put(safeKey, structured(item));
                }
            });
            return result;
        }
        if (value instanceof Iterable<?> values) {
            var result = new ArrayList<>();
            values.forEach(item -> result.add(structured(item)));
            return result;
        }
        return diagnostic(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    public static Map<String, ?> structuredFacts(Map<String, ?> value) {
        return (Map<String, ?>) structured(value);
    }

    private static boolean isSecretKey(String value) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT)
            .matches(".*(?:password|passwd|secret|token|api[_-]?key).*");
    }
}
