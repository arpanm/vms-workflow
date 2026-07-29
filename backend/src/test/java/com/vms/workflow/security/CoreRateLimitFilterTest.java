package com.vms.workflow.security;

import com.vms.workflow.application.CanonicalEvidenceHasher;
import com.vms.workflow.application.CertificationSecurityEventService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CoreRateLimitFilterTest {
    @Test
    void selectsOnlyHighRiskPostFamilies() {
        CoreRateLimitFilter filter = filter(10, 10, 10);

        assertFalse(filter.shouldNotFilter(
            new MockHttpServletRequest("POST", "/api/v1/attendance/punches")));
        assertFalse(filter.shouldNotFilter(
            new MockHttpServletRequest(
                "POST", "/api/v1/integrations/linear/webhook/connection")));
        assertFalse(filter.shouldNotFilter(
            new MockHttpServletRequest("POST", "/api/v1/migrations/jobs")));
        assertTrue(filter.shouldNotFilter(
            new MockHttpServletRequest("GET", "/api/v1/migrations/jobs")));
        assertTrue(filter.shouldNotFilter(
            new MockHttpServletRequest("POST", "/api/v1/finance/exports")));
    }

    @Test
    void rejectsUnsafeConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> filter(0, 10, 10));
        assertThrows(IllegalArgumentException.class, () -> filter(10, -1, 10));
        assertThrows(IllegalArgumentException.class, () -> filter(10, 10, 100_001));
    }

    @Test
    void incrementsOnceAndRejectsAtThresholdAcrossRandomWebhookIds()
        throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CanonicalEvidenceHasher hasher = mock(CanonicalEvidenceHasher.class);
        CertificationSecurityEventService events =
            mock(CertificationSecurityEventService.class);
        SecurityProblemWriter problems = mock(SecurityProblemWriter.class);
        jakarta.servlet.FilterChain chain = mock(jakarta.servlet.FilterChain.class);
        when(hasher.sha256(anyString())).thenAnswer(call -> call.getArgument(0));
        when(jdbc.queryForObject(
            anyString(), org.mockito.ArgumentMatchers.eq(Integer.class),
            any(), any(), any())).thenReturn(2);
        CoreRateLimitFilter filter = new CoreRateLimitFilter(
            jdbc, hasher, events, problems, 10, 10, 1, "");
        MockHttpServletRequest request = new MockHttpServletRequest(
            "POST", "/api/v1/integrations/linear/webhook/random-connection-id");
        request.setRemoteAddr("192.0.2.10");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(jdbc).queryForObject(
            anyString(), org.mockito.ArgumentMatchers.eq(Integer.class),
            org.mockito.ArgumentMatchers.eq(
                "callback:linear-webhook:unknown"),
            org.mockito.ArgumentMatchers.eq("192.0.2.10"),
            org.mockito.ArgumentMatchers.eq("LINEAR_WEBHOOK"));
        assertEquals("60", response.getHeader("Retry-After"));
        verify(problems).write(
            request, response, 429, "Too Many Requests",
            "The request rate limit was exceeded. Retry later.");
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void forwardedClientAddressIsUsedOnlyFromAnExplicitTrustedProxy()
        throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CanonicalEvidenceHasher hasher = mock(CanonicalEvidenceHasher.class);
        when(hasher.sha256(anyString())).thenAnswer(call -> call.getArgument(0));
        when(jdbc.queryForObject(
            anyString(), org.mockito.ArgumentMatchers.eq(Integer.class),
            any(), any(), any())).thenReturn(1);
        CoreRateLimitFilter filter = new CoreRateLimitFilter(
            jdbc, hasher, mock(CertificationSecurityEventService.class),
            mock(SecurityProblemWriter.class), 10, 10, 10, "10.0.0.5");
        MockHttpServletRequest request = new MockHttpServletRequest(
            "POST", "/api/v1/integrations/linear/webhook/not-a-uuid");
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("X-Forwarded-For", "198.51.100.8, 10.0.0.5");

        filter.doFilter(request, new MockHttpServletResponse(),
            mock(jakarta.servlet.FilterChain.class));

        verify(jdbc).queryForObject(
            anyString(), org.mockito.ArgumentMatchers.eq(Integer.class),
            org.mockito.ArgumentMatchers.eq(
                "callback:linear-webhook:unknown"),
            org.mockito.ArgumentMatchers.eq("198.51.100.8"),
            org.mockito.ArgumentMatchers.eq("LINEAR_WEBHOOK"));
    }

    @Test
    void untrustedCallerCannotSpoofForwardedClientAddress()
        throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CanonicalEvidenceHasher hasher = mock(CanonicalEvidenceHasher.class);
        when(hasher.sha256(anyString())).thenAnswer(call -> call.getArgument(0));
        when(jdbc.queryForObject(
            anyString(), org.mockito.ArgumentMatchers.eq(Integer.class),
            any(), any(), any())).thenReturn(1);
        CoreRateLimitFilter filter = new CoreRateLimitFilter(
            jdbc, hasher, mock(CertificationSecurityEventService.class),
            mock(SecurityProblemWriter.class), 10, 10, 10, "10.0.0.5");
        MockHttpServletRequest request = new MockHttpServletRequest(
            "POST", "/api/v1/integrations/linear/webhook/not-a-uuid");
        request.setRemoteAddr("203.0.113.9");
        request.addHeader("X-Forwarded-For", "198.51.100.8");

        filter.doFilter(request, new MockHttpServletResponse(),
            mock(jakarta.servlet.FilterChain.class));

        verify(jdbc).queryForObject(
            anyString(), org.mockito.ArgumentMatchers.eq(Integer.class),
            org.mockito.ArgumentMatchers.eq(
                "callback:linear-webhook:unknown"),
            org.mockito.ArgumentMatchers.eq("203.0.113.9"),
            org.mockito.ArgumentMatchers.eq("LINEAR_WEBHOOK"));
    }

    @Test
    void trustedProxyChainSelectsFirstUntrustedAddressFromTheRight()
        throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CanonicalEvidenceHasher hasher = mock(CanonicalEvidenceHasher.class);
        when(hasher.sha256(anyString())).thenAnswer(call -> call.getArgument(0));
        when(jdbc.queryForObject(
            anyString(), org.mockito.ArgumentMatchers.eq(Integer.class),
            any(), any(), any())).thenReturn(1);
        CoreRateLimitFilter filter = new CoreRateLimitFilter(
            jdbc, hasher, mock(CertificationSecurityEventService.class),
            mock(SecurityProblemWriter.class), 10, 10, 10,
            "10.0.0.5,10.0.0.6");
        MockHttpServletRequest request = new MockHttpServletRequest(
            "POST", "/api/v1/integrations/linear/webhook/not-a-uuid");
        request.setRemoteAddr("10.0.0.5");
        request.addHeader(
            "X-Forwarded-For",
            "203.0.113.200, 198.51.100.8, 10.0.0.6");

        filter.doFilter(request, new MockHttpServletResponse(),
            mock(jakarta.servlet.FilterChain.class));

        verify(jdbc).queryForObject(
            anyString(), org.mockito.ArgumentMatchers.eq(Integer.class),
            org.mockito.ArgumentMatchers.eq(
                "callback:linear-webhook:unknown"),
            org.mockito.ArgumentMatchers.eq("198.51.100.8"),
            org.mockito.ArgumentMatchers.eq("LINEAR_WEBHOOK"));
    }

    private CoreRateLimitFilter filter(
        int attendance,
        int migration,
        int webhook
    ) {
        return new CoreRateLimitFilter(
            mock(JdbcTemplate.class),
            mock(CanonicalEvidenceHasher.class),
            mock(CertificationSecurityEventService.class),
            mock(SecurityProblemWriter.class),
            attendance, migration, webhook, "");
    }
}
