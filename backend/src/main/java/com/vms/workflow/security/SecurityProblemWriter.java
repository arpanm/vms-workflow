package com.vms.workflow.security;

import com.vms.workflow.infrastructure.CorrelationIdFilter;
import com.vms.workflow.infrastructure.SensitiveDataRedactor;
import com.vms.workflow.application.CertificationSecurityEventService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import tools.jackson.databind.ObjectMapper;

@Component
public class SecurityProblemWriter {
    private final ObjectMapper objectMapper;
    private final CertificationSecurityEventService securityEvents;

    public SecurityProblemWriter(
        ObjectMapper objectMapper,
        CertificationSecurityEventService securityEvents
    ) {
        this.objectMapper = objectMapper;
        this.securityEvents = securityEvents;
    }

    public void write(HttpServletRequest request, HttpServletResponse response,
                      int status, String title, String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail);
        problem.setTitle(title);
        problem.setType(URI.create("https://vms.example/problems/" + status));
        problem.setInstance(URI.create(
            SensitiveDataRedactor.problemInstancePath(request.getRequestURI())));
        problem.setProperty(
            "correlationId", CorrelationIdFilter.from(request).toString());
        String securityEvent = switch (status) {
            case 401 -> "HTTP_AUTHENTICATION_DENIED";
            case 403 -> "HTTP_AUTHORIZATION_DENIED";
            case 429 -> "HTTP_RATE_LIMITED";
            default -> "HTTP_SAFETY_CONTROL_DENIED";
        };
        securityEvents.recordBestEffort(
            null,
            securityEvent,
            null, "HTTP_REQUEST", null, "DENIED",
            status == 401 ? "AUTHENTICATION_REQUIRED"
                : status == 429 ? "RATE_LIMIT_EXCEEDED"
                : status == 403 ? "ACCESS_DENIED"
                : "SAFETY_CONTROL_UNAVAILABLE",
            java.util.Map.of(
                "status", status,
                "pathTemplate", redactedPath(request.getRequestURI())));
        response.setStatus(status);
        response.setHeader(
            CorrelationIdFilter.HEADER,
            CorrelationIdFilter.from(request).toString());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }

    private String redactedPath(String value) {
        if (value == null) {
            return "";
        }
        return SensitiveDataRedactor.diagnostic(value);
    }
}
