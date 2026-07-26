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
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class CertificationRateLimitFilter extends OncePerRequestFilter {
    private final JdbcTemplate jdbc;
    private final CanonicalEvidenceHasher hasher;
    private final CertificationSecurityEventService securityEvents;
    private final SecurityProblemWriter problems;
    private final int mutationLimit;
    private final int actionLimit;
    private final int inboundLimit;
    private final int replayLimit;
    private final AtomicLong calls = new AtomicLong();

    public CertificationRateLimitFilter(
        JdbcTemplate jdbc,
        CanonicalEvidenceHasher hasher,
        CertificationSecurityEventService securityEvents,
        SecurityProblemWriter problems,
        @Value("${vms.certification.rate-limit.mutations-per-minute:120}")
        int mutationLimit,
        @Value("${vms.certification.rate-limit.actions-per-minute:20}")
        int actionLimit,
        @Value("${vms.certification.rate-limit.inbound-per-minute:30}")
        int inboundLimit,
        @Value("${vms.certification.rate-limit.replays-per-minute:10}")
        int replayLimit
    ) {
        this.jdbc = jdbc;
        this.hasher = hasher;
        this.securityEvents = securityEvents;
        this.problems = problems;
        this.mutationLimit = positive(mutationLimit);
        this.actionLimit = positive(actionLimit);
        this.inboundLimit = positive(inboundLimit);
        this.replayLimit = positive(replayLimit);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod())
            || !request.getRequestURI().startsWith("/api/v1/certification/");
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
        int limit = limit(operation);
        String actor = authentication.getName();
        String actorHash = hasher.sha256(actor);
        String clientHash = hasher.sha256(
            request.getRemoteAddr() == null
                ? "unknown" : request.getRemoteAddr());
        int count;
        try {
            count = jdbc.queryForObject("""
                INSERT INTO certification_rate_limit_buckets
                    (actor_subject_hash, client_address_hash,
                     operation, bucket_start, request_count)
                VALUES (?, ?, ?, date_trunc('minute', CURRENT_TIMESTAMP), 1)
                ON CONFLICT (
                    actor_subject_hash, client_address_hash,
                    operation, bucket_start
                )
                DO UPDATE SET request_count =
                    certification_rate_limit_buckets.request_count + 1
                RETURNING request_count
                """, Integer.class, actorHash, clientHash, operation);
            if (calls.incrementAndGet() % 1_000 == 0) {
                jdbc.update("""
                    DELETE FROM certification_rate_limit_buckets
                    WHERE bucket_start <
                        CURRENT_TIMESTAMP - INTERVAL '2 days'
                    """);
            }
        } catch (RuntimeException exception) {
            securityEvents.recordBestEffort(
                null, "F04_RATE_LIMIT_STORE_FAILURE", actor,
                "HTTP_REQUEST", null, "DENIED",
                "RATE_LIMIT_STATE_UNAVAILABLE",
                Map.of("operation", operation));
            problems.write(
                request, response, 503, "Service Unavailable",
                "A required request safety control is temporarily unavailable.");
            return;
        }
        if (count > limit) {
            securityEvents.recordBestEffort(
                null, "F04_RATE_LIMIT_EXCEEDED", actor,
                "HTTP_REQUEST", null, "DENIED",
                "RATE_LIMIT_EXCEEDED",
                Map.of("operation", operation, "limit", limit));
            response.setHeader("Retry-After", "60");
            problems.write(
                request, response, 429, "Too Many Requests",
                "The request rate limit was exceeded. Retry later.");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private String operation(String path) {
        if (path.endsWith("/actions")) {
            return "CONFIRMATION_ACTION";
        }
        if (path.contains("/inbound-messages")
            || path.contains("/manual-evidence")) {
            return "INBOUND_RESTRICTED";
        }
        if (path.endsWith("/replays")) {
            return "NOTIFICATION_REPLAY";
        }
        return "CERTIFICATION_MUTATION";
    }

    private int limit(String operation) {
        return switch (operation) {
            case "CONFIRMATION_ACTION" -> actionLimit;
            case "INBOUND_RESTRICTED" -> inboundLimit;
            case "NOTIFICATION_REPLAY" -> replayLimit;
            default -> mutationLimit;
        };
    }

    private static int positive(int value) {
        if (value < 1 || value > 100_000) {
            throw new IllegalArgumentException(
                "Certification rate limit is outside the supported range.");
        }
        return value;
    }
}
