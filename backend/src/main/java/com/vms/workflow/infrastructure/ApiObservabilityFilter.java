package com.vms.workflow.infrastructure;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.time.Duration;

/**
 * API-wide telemetry uses controller route templates, status families and
 * controlled outcomes. Raw URLs, query values, subjects and tenant identifiers
 * are never metric labels.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 15)
public final class ApiObservabilityFilter extends OncePerRequestFilter {
    private static final Logger LOGGER =
        LoggerFactory.getLogger(ApiObservabilityFilter.class);
    private final MeterRegistry registry;

    public ApiObservabilityFilter(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        Timer.Sample sample = Timer.start(registry);
        try {
            filterChain.doFilter(request, response);
        } finally {
            int status = response.getStatus();
            String route = route(request);
            String outcome = outcome(status);
            String method = controlledMethod(request.getMethod());
            String family = statusFamily(status);
            Counter.builder("vms.api.request.outcomes")
                .description("API request outcomes without tenant or object labels")
                .tag("method", method)
                .tag("route", route)
                .tag("status", family)
                .tag("outcome", outcome)
                .register(registry)
                .increment();
            sample.stop(Timer.builder("vms.api.request.duration")
                .description("API request duration by route template")
                .tag("method", method)
                .tag("route", route)
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofMinutes(10))
                .register(registry));
            if (status == 401 || status == 403) {
                Counter.builder("vms.api.authorization.denials")
                    .description("Authentication and authorization denials")
                    .tag("method", method)
                    .tag("route", route)
                    .tag("status", family)
                    .register(registry)
                    .increment();
                LOGGER.warn(
                    "api_authorization_denied correlationId={} method={} route={} status={}",
                    CorrelationIdFilter.from(request), method, route, status);
            } else if (status >= 500) {
                LOGGER.error(
                    "api_server_error correlationId={} method={} route={} status={}",
                    CorrelationIdFilter.from(request), method, route, status);
            }
        }
    }

    private String route(HttpServletRequest request) {
        Object value = request.getAttribute(
            HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (value instanceof String pattern
            && pattern.startsWith("/api/")
            && pattern.length() <= 180
            && pattern.matches("[A-Za-z0-9_./{}*:-]+")) {
            return pattern;
        }
        return "UNMATCHED";
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

    private String statusFamily(int status) {
        int family = status / 100;
        return family >= 1 && family <= 5 ? family + "xx" : "unknown";
    }

    private String controlledMethod(String value) {
        return switch (value) {
            case "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS" ->
                value;
            default -> "OTHER";
        };
    }
}
