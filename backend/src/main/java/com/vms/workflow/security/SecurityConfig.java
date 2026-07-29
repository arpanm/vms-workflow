package com.vms.workflow.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableMethodSecurity
@ConditionalOnWebApplication(
    type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(
        HttpSecurity http,
        SecurityProblemWriter problems,
        CertificationRateLimitFilter certificationRateLimit,
        FinanceRateLimitFilter financeRateLimit,
        CoreRateLimitFilter coreRateLimit
    ) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/actuator/health",
                    "/actuator/health/liveness",
                    "/actuator/health/readiness").permitAll()
                .requestMatchers("/actuator/**").denyAll()
                .requestMatchers("/api/v1/integrations/linear/webhook/**").permitAll()
                .requestMatchers("/api/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").authenticated()
                .anyRequest().denyAll())
            .oauth2ResourceServer(oauth -> oauth
                .jwt(Customizer.withDefaults())
                .authenticationEntryPoint((request, response, exception) ->
                    problems.write(request, response, 401, "Unauthorized", "A valid bearer token is required.")))
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, exception) ->
                    problems.write(request, response, 401, "Unauthorized", "A valid bearer token is required."))
                .accessDeniedHandler((request, response, exception) ->
                    problems.write(request, response, 403, "Forbidden", "The authenticated identity is not authorized for this resource.")))
            .addFilterAfter(
                certificationRateLimit,
                BearerTokenAuthenticationFilter.class)
            .addFilterAfter(
                financeRateLimit,
                CertificationRateLimitFilter.class)
            .addFilterAfter(
                coreRateLimit,
                FinanceRateLimitFilter.class)
            .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
        @Value("${vms.security.cors.allowed-origins:}") String configuredOrigins
    ) {
        List<String> origins = Arrays.stream(configuredOrigins.split(","))
            .map(String::strip)
            .filter(value -> !value.isEmpty())
            .toList();
        if (origins.stream().anyMatch(value ->
            "*".equals(value) || value.contains("*") || "null".equals(value))) {
            throw new IllegalArgumentException(
                "CORS origins must be exact and cannot contain wildcards or null.");
        }
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(
            List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
            "Authorization", "Content-Type", "X-Correlation-Id",
            "Idempotency-Key", "If-Match", "If-None-Match",
            "Linear-Signature", "Linear-Delivery", "Linear-Event"));
        configuration.setExposedHeaders(
            List.of("X-Correlation-Id", "ETag", "Retry-After", "Location"));
        // The API authenticates with an Authorization bearer token and does
        // not accept browser credentials/cookies.
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(600L);
        UrlBasedCorsConfigurationSource source =
            new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        source.registerCorsConfiguration("/v3/api-docs/**", configuration);
        return source;
    }

    @Bean
    FilterRegistrationBean<CertificationRateLimitFilter>
    disableCertificationRateLimitServletRegistration(
        CertificationRateLimitFilter filter
    ) {
        return securityChainOnly(filter);
    }

    @Bean
    FilterRegistrationBean<FinanceRateLimitFilter>
    disableFinanceRateLimitServletRegistration(FinanceRateLimitFilter filter) {
        return securityChainOnly(filter);
    }

    @Bean
    FilterRegistrationBean<CoreRateLimitFilter>
    disableCoreRateLimitServletRegistration(CoreRateLimitFilter filter) {
        return securityChainOnly(filter);
    }

    private static <T extends jakarta.servlet.Filter>
    FilterRegistrationBean<T> securityChainOnly(T filter) {
        FilterRegistrationBean<T> registration =
            new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
                          @Value("${vms.security.audience}") String audience,
                          @Value("${vms.security.issuer}") String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            .build();
        OAuth2TokenValidator<Jwt> audienceValidator = jwt -> jwt.getAudience().contains(audience)
            ? OAuth2TokenValidatorResult.success()
            : OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", "Required audience is missing.", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer), audienceValidator));
        return decoder;
    }
}
