package com.ir.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param issuerUri  must equal the token's {@code iss} — the URL the BROWSER uses (localhost:8180)
 * @param jwkSetUri  where containers fetch signing keys (keycloak:8080)
 * @param audience   required entry in the token's {@code aud}
 */
@ConfigurationProperties("ir.security")
public record IrSecurityProperties(String issuerUri, String jwkSetUri, String audience) {
}
