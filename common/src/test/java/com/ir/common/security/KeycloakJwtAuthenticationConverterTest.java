package com.ir.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakJwtAuthenticationConverterTest {

    @Test
    void mapsRealmRolesAndScopesAndUsesSubAsPrincipal() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none")
                .subject("4b1c-sub")
                .claim("preferred_username", "op1")
                .claim("realm_access", Map.of("roles", List.of("operator", "offline_access")))
                .claim("scope", "openid profile")
                .build();

        var auth = new KeycloakJwtAuthenticationConverter().convert(jwt);

        assertThat(auth.getName()).isEqualTo("4b1c-sub");
        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_operator", "ROLE_offline_access", "SCOPE_openid", "SCOPE_profile");
    }

    @Test
    void toleratesMissingRealmAccess() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("s").build();

        assertThat(new KeycloakJwtAuthenticationConverter().convert(jwt).getAuthorities()).isEmpty();
    }
}
