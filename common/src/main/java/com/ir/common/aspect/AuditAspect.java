package com.ir.common.aspect;

import com.ir.common.event.AuditEvent;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cross-cutting audit: every {@link Audited} method emits exactly one {@link AuditEvent}
 * (outcome OK or FAILED) with the authenticated actor, without touching business code.
 * <p>
 * Ordered just inside the outermost position so it wraps {@code @Transactional}: OK is only
 * published after the transaction committed, and a rollback is reported as FAILED.
 */
@Slf4j
@Aspect
@Order(Ordered.LOWEST_PRECEDENCE - 1)
public class AuditAspect {

    static final String SYSTEM_ACTOR = "system";
    static final String UNKNOWN_ENTITY = "unknown";

    private final AuditEventPublisher publisher;
    private final String serviceName;

    public AuditAspect(AuditEventPublisher publisher, String serviceName) {
        this.publisher = publisher;
        this.serviceName = serviceName;
    }

    // Lowercase `audited` binds the annotation instance to the parameter. Writing the type
    // name (`@annotation(Audited)`) with a bound parameter fails at startup ("formal unbound").
    @Around("@annotation(audited)")
    public Object around(ProceedingJoinPoint pjp, Audited audited) throws Throwable {
        String actor = currentActor();
        long t0 = System.nanoTime();
        Object result;
        try {
            result = pjp.proceed();
        } catch (Throwable ex) {
            publishSafely(audited, extractEntityId(pjp, audited, null), actor, AuditEvent.FAILED,
                    details(pjp, t0, ex));
            throw ex;
        }
        publishSafely(audited, extractEntityId(pjp, audited, result), actor, AuditEvent.OK,
                details(pjp, t0, null));
        return result;
    }

    private void publishSafely(Audited audited, String entityId, String actor, String outcome,
                               Map<String, String> details) {
        try {
            publisher.publish(AuditEvent.of(audited.action(), audited.entityType(), entityId, actor,
                    serviceName, outcome, details));
        } catch (RuntimeException e) {
            // Availability over completeness; the outbox variant removes this gap (see README).
            log.error("AUDIT publish failed action={} entityId={}", audited.action(), entityId, e);
        }
    }

    static String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return SYSTEM_ACTOR; // Kafka listeners and schedulers run without a user
        }
        return auth.getName(); // JWT `sub` — see KeycloakJwtAuthenticationConverter
    }

    private static String extractEntityId(ProceedingJoinPoint pjp, Audited audited, Object result) {
        String param = audited.entityIdParam();
        if (!param.isEmpty()) {
            String[] names = ((MethodSignature) pjp.getSignature()).getParameterNames();
            Object[] args = pjp.getArgs();
            if (names != null) {
                for (int i = 0; i < names.length; i++) {
                    if (names[i].equals(param) && args[i] != null) {
                        return args[i].toString();
                    }
                }
            }
            log.warn("AUDIT entityIdParam '{}' not found on {} (compiled without -parameters?)",
                    param, pjp.getSignature().toShortString());
        }
        if (result instanceof AuditIdentifiable identifiable) {
            return identifiable.auditEntityId();
        }
        return UNKNOWN_ENTITY;
    }

    /** Method name, duration and error class only — never raw arguments (PII, locations). */
    private static Map<String, String> details(ProceedingJoinPoint pjp, long t0, Throwable ex) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("method", pjp.getSignature().toShortString());
        details.put("durationMs", String.valueOf((System.nanoTime() - t0) / 1_000_000));
        if (ex != null) {
            details.put("error", ex.getClass().getSimpleName());
        }
        return details;
    }
}
