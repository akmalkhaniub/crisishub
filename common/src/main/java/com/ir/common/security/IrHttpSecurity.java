package com.ir.common.security;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;

/**
 * Baseline every service applies to its own {@code SecurityFilterChain}: stateless bearer-token
 * resource server, health endpoints public, everything else authenticated. Fine-grained rules
 * live in {@code @PreAuthorize} and in the service layer.
 */
public final class IrHttpSecurity {

    private IrHttpSecurity() {
    }

    public static HttpSecurity resourceServer(HttpSecurity http, KeycloakJwtAuthenticationConverter converter)
            throws Exception {
        return http
                .csrf(csrf -> csrf.disable()) // no cookies/sessions: bearer tokens only
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)));
    }
}
