package com.vms.workflow.infrastructure;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Uniform browser-facing policy for API, error, documentation and management
 * responses. Proxy transport headers are accepted only from explicitly trusted
 * remote addresses; an empty allow-list therefore fails closed.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SecurityHeadersFilter extends OncePerRequestFilter {
    static final String CSP = "default-src 'self'; object-src 'none'; "
        + "frame-ancestors 'none'; base-uri 'self'; form-action 'self'";
    private static final String HSTS =
        "max-age=31536000; includeSubDomains";
    private final Set<String> trustedProxyAddresses;

    public SecurityHeadersFilter(
        @Value("${vms.security.trusted-proxy-addresses:}")
        String trustedProxyAddresses
    ) {
        this.trustedProxyAddresses = Arrays.stream(
                trustedProxyAddresses.split(","))
            .map(String::strip)
            .filter(value -> !value.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        response.setHeader("Content-Security-Policy", CSP);
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader(
            "Permissions-Policy",
            "camera=(), geolocation=(), microphone=(), payment=(), usb=()");
        response.setHeader(
            "Cache-Control",
            "no-store, no-cache, max-age=0, must-revalidate, private");
        response.setHeader("Pragma", "no-cache");
        if (isSecureAtTrustedBoundary(request)) {
            response.setHeader("Strict-Transport-Security", HSTS);
        }
        filterChain.doFilter(request, response);
    }

    private boolean isSecureAtTrustedBoundary(HttpServletRequest request) {
        if (request.isSecure()) {
            return true;
        }
        if (!trustedProxyAddresses.contains(request.getRemoteAddr())) {
            return false;
        }
        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        return forwardedProto != null
            && forwardedProto.indexOf(',') < 0
            && "https".equalsIgnoreCase(forwardedProto.strip());
    }
}
