package com.vms.workflow.api;

import com.vms.workflow.infrastructure.CorrelationIdFilter;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOGGER =
        LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(EntityNotFoundException.class)
    ProblemDetail notFound(EntityNotFoundException exception, HttpServletRequest request) {
        return problem(
            HttpStatus.NOT_FOUND, "Not Found", "Resource not found.", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail forbidden(AccessDeniedException exception, HttpServletRequest request) {
        return problem(
            HttpStatus.FORBIDDEN, "Forbidden",
            "The authenticated identity is not authorized for this resource.",
            request);
    }

    @ExceptionHandler({DomainConflictException.class, DataIntegrityViolationException.class})
    ProblemDetail conflict(Exception exception, HttpServletRequest request) {
        String detail = exception instanceof DomainConflictException
            ? redactedDetail(exception.getMessage())
            : "The request conflicts with an existing or constrained record.";
        ProblemDetail value = problem(HttpStatus.CONFLICT, "Conflict", detail, request);
        if (exception instanceof DomainConflictException domain) {
            value.setProperty("code", domain.getCode());
            if (domain.getCurrentVersion() != null) {
                value.setProperty("currentVersion", domain.getCurrentVersion());
            }
        }
        return value;
    }

    @ExceptionHandler({
        IllegalArgumentException.class,
        ConstraintViolationException.class,
        MethodArgumentNotValidException.class,
        MissingServletRequestParameterException.class,
        MissingRequestHeaderException.class,
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class
    })
    ProblemDetail badRequest(Exception exception, HttpServletRequest request) {
        return problem(
            HttpStatus.BAD_REQUEST, "Bad Request",
            "The request syntax, headers or validated fields are invalid.",
            request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ProblemDetail unsupportedMediaType(
        HttpMediaTypeNotSupportedException exception,
        HttpServletRequest request
    ) {
        return problem(
            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            "Unsupported Media Type",
            "The request content type is not supported.",
            request);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception exception, HttpServletRequest request) {
        LOGGER.error(
            "Unhandled API failure correlationId={} exceptionType={}",
            CorrelationIdFilter.from(request),
            exception.getClass().getSimpleName());
        return problem(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Internal Server Error",
            "The request could not be completed. Use the correlation ID when contacting support.",
            request);
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail, HttpServletRequest request) {
        ProblemDetail value = ProblemDetail.forStatusAndDetail(status, detail == null ? title : detail);
        value.setTitle(title);
        value.setType(URI.create("https://vms.example/problems/" + status.value()));
        value.setInstance(URI.create(request.getRequestURI()));
        value.setProperty(
            "correlationId", CorrelationIdFilter.from(request).toString());
        return value;
    }

    private String redactedDetail(String value) {
        if (value == null || value.isBlank()) {
            return "The request conflicts with current workflow state.";
        }
        String redacted = value
            .replaceAll(
                "(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}",
                "[redacted-email]")
            .replaceAll(
                "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
                "[redacted-id]")
            .replaceAll("(?i)\\buser-[a-z0-9._-]+\\b", "[redacted-subject]");
        return redacted.substring(0, Math.min(redacted.length(), 1_000));
    }
}
