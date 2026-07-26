package com.vms.workflow.infrastructure;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Correlation-Id";
    public static final String ATTRIBUTE =
        CorrelationIdFilter.class.getName() + ".correlationId";

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        UUID correlationId = parseOrCreate(request.getHeader(HEADER));
        request.setAttribute(ATTRIBUTE, correlationId);
        response.setHeader(HEADER, correlationId.toString());
        CURRENT.set(correlationId);
        MDC.put("correlationId", correlationId.toString());
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("correlationId");
            CURRENT.remove();
        }
    }

    public static UUID currentOrNew() {
        UUID value = CURRENT.get();
        return value == null ? UUID.randomUUID() : value;
    }

    public static UUID from(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        return value instanceof UUID id ? id : currentOrNew();
    }

    private UUID parseOrCreate(String supplied) {
        if (supplied == null || supplied.isBlank() || supplied.length() > 64) {
            return UUID.randomUUID();
        }
        try {
            return UUID.fromString(supplied.strip());
        } catch (IllegalArgumentException ignored) {
            return UUID.randomUUID();
        }
    }
}
