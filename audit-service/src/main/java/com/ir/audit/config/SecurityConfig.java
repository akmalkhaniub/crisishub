package com.ir.audit.config;

import com.ir.common.security.IrHttpSecurity;
import com.ir.common.security.KeycloakJwtAuthenticationConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, KeycloakJwtAuthenticationConverter converter)
            throws Exception {
        return IrHttpSecurity.resourceServer(http, converter).build();
    }
}
