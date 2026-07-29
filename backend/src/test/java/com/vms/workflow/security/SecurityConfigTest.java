package com.vms.workflow.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecurityConfigTest {
    private final SecurityConfig security = new SecurityConfig();

    @Test
    void bearerCorsUsesExactOriginsWithoutBrowserCredentials() {
        CorsConfigurationSource source =
            security.corsConfigurationSource(
                "https://app.example.test, http://127.0.0.1:5173");
        CorsConfiguration configuration = source.getCorsConfiguration(
            new MockHttpServletRequest("OPTIONS", "/api/v1/me"));

        assertEquals(
            java.util.List.of(
                "https://app.example.test", "http://127.0.0.1:5173"),
            configuration.getAllowedOrigins());
        assertFalse(Boolean.TRUE.equals(configuration.getAllowCredentials()));
    }

    @Test
    void wildcardLikeAndNullOriginsFailAtStartup() {
        assertThrows(IllegalArgumentException.class,
            () -> security.corsConfigurationSource("*"));
        assertThrows(IllegalArgumentException.class,
            () -> security.corsConfigurationSource("https://*.example.test"));
        assertThrows(IllegalArgumentException.class,
            () -> security.corsConfigurationSource("null"));
    }

    @Test
    void explicitSecurityChainFiltersAreNotAutoRegisteredAsServletFilters() {
        CertificationRateLimitFilter certification =
            org.mockito.Mockito.mock(CertificationRateLimitFilter.class);
        FinanceRateLimitFilter finance =
            org.mockito.Mockito.mock(FinanceRateLimitFilter.class);
        CoreRateLimitFilter core =
            org.mockito.Mockito.mock(CoreRateLimitFilter.class);

        assertFalse(security
            .disableCertificationRateLimitServletRegistration(certification)
            .isEnabled());
        assertFalse(security
            .disableFinanceRateLimitServletRegistration(finance)
            .isEnabled());
        assertFalse(security
            .disableCoreRateLimitServletRegistration(core)
            .isEnabled());
    }
}
