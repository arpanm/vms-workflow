package com.vms.workflow.infrastructure;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ApiObservabilityFilterTest {
    @Test
    void recordsRouteTemplateAndDenialWithoutRawResourceIdentifier()
        throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ApiObservabilityFilter filter = new ApiObservabilityFilter(registry);
        MockHttpServletRequest request = new MockHttpServletRequest(
            "GET", "/api/v1/engagements/private-object-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (currentRequest, currentResponse) -> {
            currentRequest.setAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                "/api/v1/engagements/{id}");
            ((MockHttpServletResponse) currentResponse).setStatus(403);
        });

        assertEquals(1.0, registry.get("vms.api.authorization.denials")
            .tag("method", "GET")
            .tag("route", "/api/v1/engagements/{id}")
            .tag("status", "4xx")
            .counter().count());
        assertNotNull(registry.get("vms.api.request.duration")
            .tag("route", "/api/v1/engagements/{id}")
            .timer());
        assertFalse(registry.getMeters().stream()
            .flatMap(meter -> meter.getId().getTags().stream())
            .anyMatch(tag -> tag.getValue().contains("private-object-123")));
    }

    @Test
    void unmatchedPathsCollapseToOneControlledTag() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ApiObservabilityFilter filter = new ApiObservabilityFilter(registry);
        MockHttpServletRequest request = new MockHttpServletRequest(
            "GET", "/api/v1/not-found/secret-id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (currentRequest, currentResponse) ->
            ((MockHttpServletResponse) currentResponse).setStatus(404));

        assertEquals(1.0, registry.get("vms.api.request.outcomes")
            .tag("route", "UNMATCHED")
            .tag("outcome", "client_error")
            .counter().count());
    }
}
