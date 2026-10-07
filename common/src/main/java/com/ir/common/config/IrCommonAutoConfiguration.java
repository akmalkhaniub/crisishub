package com.ir.common.config;

import com.ir.common.aspect.AuditAspect;
import com.ir.common.aspect.AuditEventPublisher;
import com.ir.common.aspect.KafkaAuditEventPublisher;
import com.ir.common.aspect.LoggingAuditEventPublisher;
import com.ir.common.error.ProblemExceptionHandler;
import com.ir.common.security.IrSecurityProperties;
import com.ir.common.security.KeycloakJwtAuthenticationConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.databind.json.JsonMapper;

/** Wires the shared building blocks into every service that depends on {@code common}. */
@AutoConfiguration(afterName = {
        "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration",
        "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration"})
@EnableConfigurationProperties(IrSecurityProperties.class)
public class IrCommonAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(AuditEventPublisher.class)
    @ConditionalOnBean(KafkaTemplate.class)
    AuditEventPublisher kafkaAuditEventPublisher(KafkaTemplate<String, String> kafka, JsonMapper json) {
        return new KafkaAuditEventPublisher(kafka, json);
    }

    /** Fallback for services/tests without Kafka: audit lines go to the log only. */
    @Bean
    @ConditionalOnMissingBean(AuditEventPublisher.class)
    AuditEventPublisher loggingAuditEventPublisher() {
        return new LoggingAuditEventPublisher();
    }

    @Bean
    AuditAspect auditAspect(AuditEventPublisher publisher, @Value("${spring.application.name}") String serviceName) {
        return new AuditAspect(publisher, serviceName);
    }

    @Bean
    KeycloakJwtAuthenticationConverter keycloakJwtAuthenticationConverter() {
        return new KeycloakJwtAuthenticationConverter();
    }

    @Bean
    ProblemExceptionHandler problemExceptionHandler() {
        return new ProblemExceptionHandler();
    }

    /**
     * Keys come from the internal JWKS URL, but {@code iss} is validated against the public
     * issuer the browser used — the fix for "works in the browser, 401 in Docker".
     */
    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    @ConditionalOnProperty("ir.security.jwk-set-uri")
    JwtDecoder jwtDecoder(IrSecurityProperties props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(props.jwkSetUri()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(props.issuerUri()),
                audienceValidator(props.audience())));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> audienceValidator(String audience) {
        return jwt -> jwt.getAudience() != null && jwt.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "Required audience missing: " + audience, null));
    }

    /** 3 retries, then the record goes to {@code <topic>-dlt}. */
    @Bean
    @ConditionalOnBean(KafkaTemplate.class)
    CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafka) {
        return new DefaultErrorHandler(new DeadLetterPublishingRecoverer(kafka), new FixedBackOff(1000L, 3));
    }
}
