package com.vms.workflow.infrastructure;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class SecurityHeadersFilterTest {
    private final SecurityHeadersFilter filter = new SecurityHeadersFilter("");
    private final FilterChain chain = mock(FilterChain.class);

    @Test
    void writesBrowserSecurityAndNoStoreHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertEquals(SecurityHeadersFilter.CSP,
            response.getHeader("Content-Security-Policy"));
        assertEquals("DENY", response.getHeader("X-Frame-Options"));
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
        assertEquals("no-referrer", response.getHeader("Referrer-Policy"));
        assertEquals("camera=(), geolocation=(), microphone=(), payment=(), usb=()",
            response.getHeader("Permissions-Policy"));
        assertEquals("no-store, no-cache, max-age=0, must-revalidate, private",
            response.getHeader("Cache-Control"));
        assertNull(response.getHeader("Strict-Transport-Security"));
    }

    @Test
    void writesHstsOnlyForSecureRequests() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        request.setSecure(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertEquals(
            "max-age=31536000; includeSubDomains",
            response.getHeader("Strict-Transport-Security"));
    }

    @Test
    void ignoresForwardedProtoFromUntrustedRemoteAddress() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
            "GET", "/actuator/health");
        request.setRemoteAddr("203.0.113.25");
        request.addHeader("X-Forwarded-Proto", "https");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertNull(response.getHeader("Strict-Transport-Security"));
    }

    @Test
    void acceptsSingleHttpsProtoFromConfiguredTrustedProxy() throws Exception {
        SecurityHeadersFilter trustedFilter =
            new SecurityHeadersFilter("10.0.0.5, 10.0.0.6");
        MockHttpServletRequest request = new MockHttpServletRequest(
            "GET", "/actuator/health");
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("X-Forwarded-Proto", "https");
        MockHttpServletResponse response = new MockHttpServletResponse();

        trustedFilter.doFilter(request, response, chain);

        assertEquals(
            "max-age=31536000; includeSubDomains",
            response.getHeader("Strict-Transport-Security"));
    }

    @Test
    void rejectsAmbiguousForwardedProtoEvenFromTrustedProxy() throws Exception {
        SecurityHeadersFilter trustedFilter =
            new SecurityHeadersFilter("10.0.0.5");
        MockHttpServletRequest request = new MockHttpServletRequest(
            "GET", "/actuator/health");
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("X-Forwarded-Proto", "http, https");
        MockHttpServletResponse response = new MockHttpServletResponse();

        trustedFilter.doFilter(request, response, chain);

        assertNull(response.getHeader("Strict-Transport-Security"));
    }

    @Test
    void productionDisablesContainerForwardHeaderReinterpretation()
        throws Exception {
        var properties = new YamlPropertySourceLoader().load(
            "application-prod",
            new ClassPathResource("application-prod.yml"));

        assertEquals(
            "none",
            properties.getFirst().getProperty(
                "server.forward-headers-strategy"));
    }
}
