package com.vms.workflow.security;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * Validates a URI before it can cross an outbound provider boundary.
 *
 * <p>Callers must still disable redirects and bound response size/timeouts in
 * the HTTP client. Redirect targets must be validated again with this policy.
 */
public final class OutboundUriPolicy {
    private OutboundUriPolicy() {
    }

    public static URI requireHttpsHost(String value, Set<String> allowedHosts) {
        if (value == null || value.isBlank() || allowedHosts == null
            || allowedHosts.isEmpty()) {
            throw new IllegalArgumentException("Outbound URI is invalid.");
        }
        final URI uri;
        try {
            uri = URI.create(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Outbound URI is invalid.", exception);
        }
        String host = uri.getHost();
        boolean approvedHost = host != null && allowedHosts.stream()
            .map(allowed -> allowed.toLowerCase(Locale.ROOT))
            .anyMatch(allowed -> allowed.equals(host.toLowerCase(Locale.ROOT)));
        if (!uri.isAbsolute()
            || !"https".equalsIgnoreCase(uri.getScheme())
            || !approvedHost
            || uri.getRawUserInfo() != null
            || uri.getPort() != -1
            || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Outbound URI is not approved.");
        }
        return uri.normalize();
    }
}
