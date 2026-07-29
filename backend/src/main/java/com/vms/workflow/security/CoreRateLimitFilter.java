package com.vms.workflow.security;

import com.vms.workflow.application.CanonicalEvidenceHasher;
import com.vms.workflow.application.CertificationSecurityEventService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Durable local abuse control for high-risk endpoint families not covered by
 * the certification and finance-specific limiters.
 */
@Component
public class CoreRateLimitFilter extends OncePerRequestFilter {
    private final JdbcTemplate jdbc;
    private final CanonicalEvidenceHasher hasher;
    private final CertificationSecurityEventService securityEvents;
    private final SecurityProblemWriter problems;
    private final int attendanceLimit;
    private final int migrationLimit;
    private final int webhookLimit;
    private final Set<String> trustedProxyAddresses;
    private final AtomicLong calls = new AtomicLong();
    private static final Pattern NUMERIC_IP = Pattern.compile(
        "^(?:\\d{1,3}(?:\\.\\d{1,3}){3}|[0-9A-Fa-f:]+)$");

    public CoreRateLimitFilter(
        JdbcTemplate jdbc,
        CanonicalEvidenceHasher hasher,
        CertificationSecurityEventService securityEvents,
        SecurityProblemWriter problems,
        @Value("${vms.security.rate-limit.attendance-per-minute:120}")
        int attendanceLimit,
        @Value("${vms.security.rate-limit.migration-per-minute:60}")
        int migrationLimit,
        @Value("${vms.security.rate-limit.webhook-per-minute:300}")
        int webhookLimit,
        @Value("${vms.security.trusted-proxy-addresses:}")
        String trustedProxyAddresses
    ) {
        this.jdbc = jdbc;
        this.hasher = hasher;
        this.securityEvents = securityEvents;
        this.problems = problems;
        this.attendanceLimit = positive(attendanceLimit);
        this.migrationLimit = positive(migrationLimit);
        this.webhookLimit = positive(webhookLimit);
        this.trustedProxyAddresses = Set.copyOf(Arrays.stream(
                trustedProxyAddresses == null
                    ? new String[0] : trustedProxyAddresses.split(","))
            .map(String::strip)
            .filter(value -> !value.isEmpty())
            .toList());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod())
            || operation(request.getRequestURI()) == null;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String operation = operation(request.getRequestURI());
        Authentication authentication =
            SecurityContextHolder.getContext().getAuthentication();
        String principal = authentication != null && authentication.isAuthenticated()
            ? authentication.getName() : "callback:unauthenticated";
        try {
            if ("callback:unauthenticated".equals(principal)) {
                principal = webhookPrincipal(request.getRequestURI());
            }
            String principalHash = hasher.sha256(principal);
            String clientHash = hasher.sha256(clientAddress(request));
            int count = jdbc.queryForObject("""
                INSERT INTO f07_rate_limit_buckets(
                    principal_hash, client_address_hash, operation,
                    bucket_start, request_count
                ) VALUES (?, ?, ?, date_trunc('minute', CURRENT_TIMESTAMP), 1)
                ON CONFLICT (
                    principal_hash, client_address_hash, operation, bucket_start
                )
                DO UPDATE SET request_count =
                    f07_rate_limit_buckets.request_count + 1
                RETURNING request_count
                """, Integer.class, principalHash, clientHash, operation);
            if (calls.incrementAndGet() % 1_000 == 0) {
                jdbc.update("""
                    DELETE FROM f07_rate_limit_buckets
                    WHERE bucket_start < CURRENT_TIMESTAMP - INTERVAL '2 days'
                    """);
            }
            if (count > limit(operation)) {
                securityEvents.recordBestEffort(
                    null, "F07_RATE_LIMIT_EXCEEDED", principal,
                    "HTTP_REQUEST", null, "DENIED", "RATE_LIMIT_EXCEEDED",
                    Map.of("operation", operation, "limit", limit(operation)));
                response.setHeader("Retry-After", "60");
                problems.write(
                    request, response, 429, "Too Many Requests",
                    "The request rate limit was exceeded. Retry later.");
                return;
            }
        } catch (RuntimeException exception) {
            securityEvents.recordBestEffort(
                null, "F07_RATE_LIMIT_STORE_FAILURE", principal,
                "HTTP_REQUEST", null, "DENIED",
                "RATE_LIMIT_STATE_UNAVAILABLE",
                Map.of("operation", operation));
            problems.write(
                request, response, 503, "Service Unavailable",
                "A required request safety control is temporarily unavailable.");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private String operation(String path) {
        if (path == null) {
            return null;
        }
        if (path.equals("/api/v1/attendance/punches")
            || path.equals("/api/v1/attendance/regularizations")) {
            return "ATTENDANCE_MUTATION";
        }
        if (path.startsWith("/api/v1/migrations/")) {
            return "MIGRATION_MUTATION";
        }
        if (path.startsWith("/api/v1/integrations/linear/webhook/")) {
            return "LINEAR_WEBHOOK";
        }
        return null;
    }

    private String webhookPrincipal(String path) {
        String prefix = "/api/v1/integrations/linear/webhook/";
        if (path == null || !path.startsWith(prefix)) {
            return "callback:unauthenticated";
        }
        String rawId = path.substring(prefix.length());
        final UUID connectionId;
        try {
            connectionId = UUID.fromString(rawId);
        } catch (IllegalArgumentException exception) {
            return "callback:linear-webhook:unknown";
        }
        Boolean known = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM linear_connections WHERE id = ?
            )
            """, Boolean.class, connectionId);
        return Boolean.TRUE.equals(known)
            ? "callback:linear-webhook:" + connectionId
            : "callback:linear-webhook:unknown";
    }

    private String clientAddress(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (remote == null || remote.isBlank()) {
            return "unknown";
        }
        if (!trustedProxyAddresses.contains(remote)) {
            return remote;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return remote;
        }
        String[] chain = forwarded.split(",");
        for (int index = chain.length - 1; index >= 0; index--) {
            String candidate = chain[index].strip();
            if (!NUMERIC_IP.matcher(candidate).matches()) {
                return remote;
            }
            if (!trustedProxyAddresses.contains(candidate)) {
                return candidate;
            }
        }
        return remote;
    }

    private int limit(String operation) {
        return switch (operation) {
            case "ATTENDANCE_MUTATION" -> attendanceLimit;
            case "MIGRATION_MUTATION" -> migrationLimit;
            case "LINEAR_WEBHOOK" -> webhookLimit;
            default -> throw new IllegalArgumentException(
                "Unsupported rate-limit operation.");
        };
    }

    private static int positive(int value) {
        if (value < 1 || value > 100_000) {
            throw new IllegalArgumentException(
                "Core rate limit is outside the supported range.");
        }
        return value;
    }
}
