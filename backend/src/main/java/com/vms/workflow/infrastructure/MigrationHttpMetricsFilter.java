package com.vms.workflow.infrastructure;

import com.vms.workflow.application.MigrationMetrics;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Records bounded route families rather than raw paths, preventing job IDs and
 * other resource identifiers from becoming metric labels.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public final class MigrationHttpMetricsFilter extends OncePerRequestFilter {
    private static final String PREFIX = "/api/v1/migrations";
    private final MigrationMetrics metrics;

    public MigrationHttpMetricsFilter(MigrationMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PREFIX);
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        Timer.Sample sample = metrics.start();
        String operation = operation(
            request.getMethod(), request.getRequestURI());
        try {
            filterChain.doFilter(request, response);
        } finally {
            int status = response.getStatus();
            metrics.recordHttp(
                sample, operation, outcome(status), status);
        }
    }

    private String outcome(int status) {
        if (status == 401 || status == 403) {
            return "authorization_denied";
        }
        if (status >= 500) {
            return "server_error";
        }
        if (status >= 400) {
            return "client_error";
        }
        return "success";
    }

    private String operation(String method, String uri) {
        String suffix = uri.substring(PREFIX.length());
        if ("POST".equals(method) && "/jobs".equals(suffix)) {
            return "upload";
        }
        if (suffix.endsWith("/validate")) {
            return "validate";
        }
        if (suffix.endsWith("/commit")) {
            return "commit";
        }
        if (suffix.endsWith("/rollback")) {
            return "rollback";
        }
        if (suffix.endsWith("/reprocess")) {
            return "reprocess";
        }
        if (suffix.endsWith("/retry")) {
            return "retry";
        }
        if (suffix.endsWith("/cancel")) {
            return "cancel";
        }
        if (suffix.endsWith("/approval")
            || suffix.endsWith("/approvals")) {
            return "approval";
        }
        if (suffix.contains("/sign-offs")) {
            return "reconciliation_signoff";
        }
        if (suffix.contains("/reconciliation")) {
            return "reconciliation";
        }
        if (suffix.contains("/errors/download")) {
            return "error_export";
        }
        if (suffix.startsWith("/templates")) {
            return "template";
        }
        if (suffix.startsWith("/jobs")) {
            return "job";
        }
        if (suffix.startsWith("/retro-requests")) {
            return "retro_request";
        }
        return "other";
    }
}
