package com.vms.workflow.security;

import com.vms.workflow.application.FinanceCanonicalJson;
import com.vms.workflow.infrastructure.CorrelationIdFilter;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fail-closed per-identity/client finance mutation and mass-access control.
 */
@Component
public class FinanceRateLimitFilter extends OncePerRequestFilter {
    private final JdbcTemplate jdbc;
    private final FinanceCanonicalJson canonical;
    private final SecurityProblemWriter problems;
    private final int mutationLimit;
    private final int downloadLimit;
    private final int exportLimit;
    private final AtomicLong calls = new AtomicLong();

    public FinanceRateLimitFilter(
        JdbcTemplate jdbc,
        FinanceCanonicalJson canonical,
        SecurityProblemWriter problems,
        @Value("${vms.finance.rate-limit.mutations-per-minute:120}")
        int mutationLimit,
        @Value("${vms.finance.rate-limit.downloads-per-minute:60}")
        int downloadLimit,
        @Value("${vms.finance.rate-limit.exports-per-minute:20}")
        int exportLimit
    ) {
        this.jdbc = jdbc;
        this.canonical = canonical;
        this.problems = problems;
        this.mutationLimit = positive(mutationLimit);
        this.downloadLimit = positive(downloadLimit);
        this.exportLimit = positive(exportLimit);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod())
            || !request.getRequestURI().startsWith("/api/v1/finance/");
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        Authentication authentication =
            SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }
        String operation = operation(request.getRequestURI());
        String actorHash = canonical.sha256Text(authentication.getName());
        String clientHash = canonical.sha256Text(
            request.getRemoteAddr() == null
                ? "unknown" : request.getRemoteAddr());
        try {
            UUID organizationId = jdbc.query("""
                SELECT membership.organization_id
                FROM user_profiles profile
                JOIN memberships membership
                  ON membership.user_profile_id = profile.id
                WHERE profile.identity_subject = ?
                  AND profile.status = 'ACTIVE'
                  AND membership.status = 'ACTIVE'
                  AND membership.valid_from <= CURRENT_DATE
                  AND (membership.valid_to IS NULL
                       OR membership.valid_to >= CURRENT_DATE)
                ORDER BY membership.organization_id
                LIMIT 1
                """, rs -> rs.next()
                    ? rs.getObject(1, UUID.class) : null,
                authentication.getName());
            if (organizationId == null) {
                // Authorization remains responsible for rejecting inactive or
                // unscoped identities.  Do not mask that decision as a rate
                // limit store outage before the controller can evaluate it.
                filterChain.doFilter(request, response);
                return;
            }
            int count = jdbc.queryForObject("""
                INSERT INTO f05_rate_limit_buckets(
                    actor_subject_hash, client_address_hash, organization_id,
                    operation, bucket_start, request_count
                ) VALUES (?, ?, ?, ?,
                          date_trunc('minute', CURRENT_TIMESTAMP), 1)
                ON CONFLICT (
                    actor_subject_hash, client_address_hash,
                    organization_id, operation, bucket_start
                )
                DO UPDATE SET request_count =
                    f05_rate_limit_buckets.request_count + 1
                RETURNING request_count
                """, Integer.class, actorHash, clientHash,
                organizationId, operation);
            if (calls.incrementAndGet() % 1_000 == 0) {
                jdbc.update("""
                    DELETE FROM f05_rate_limit_buckets
                    WHERE bucket_start <
                        CURRENT_TIMESTAMP - INTERVAL '2 days'
                    """);
            }
            if (count > limit(operation)) {
                securityEvent(actorHash, "F05_RATE_LIMIT_EXCEEDED",
                    "RATE_LIMIT_EXCEEDED");
                response.setHeader("Retry-After", "60");
                problems.write(
                    request, response, 429, "Too Many Requests",
                    "The request rate limit was exceeded. Retry later.");
                return;
            }
        } catch (RuntimeException exception) {
            securityEvent(actorHash, "F05_RATE_LIMIT_STORE_FAILURE",
                "RATE_LIMIT_STATE_UNAVAILABLE");
            problems.write(
                request, response, 503, "Service Unavailable",
                "A required request safety control is temporarily unavailable.");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private String operation(String path) {
        if (path.endsWith("/download")) {
            return "FINANCE_DOWNLOAD";
        }
        if (path.endsWith("/exports") || path.contains("/exports/")) {
            return "FINANCE_EXPORT";
        }
        return "FINANCE_MUTATION";
    }

    private int limit(String operation) {
        return switch (operation) {
            case "FINANCE_DOWNLOAD" -> downloadLimit;
            case "FINANCE_EXPORT" -> exportLimit;
            default -> mutationLimit;
        };
    }

    private void securityEvent(
        String actorHash,
        String eventType,
        String reason
    ) {
        try {
            jdbc.update("""
                INSERT INTO f05_security_events(
                    id, event_type, result, reason_code,
                    actor_subject_hash, correlation_id
                ) VALUES (?, ?, 'DENIED', ?, ?, ?)
                """, UUID.randomUUID(), eventType, reason, actorHash,
                CorrelationIdFilter.currentOrNew());
        } catch (RuntimeException ignored) {
            // The response remains fail-closed when security telemetry is down.
        }
    }

    private static int positive(int value) {
        if (value < 1 || value > 100_000) {
            throw new IllegalArgumentException(
                "Finance rate limit is outside the supported range.");
        }
        return value;
    }
}
