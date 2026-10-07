package com.ir.common.aspect;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a public, proxied service method whose invocation must produce an {@code AuditEvent}.
 * Never put this on private methods or methods called via {@code this.} — Spring AOP proxies
 * do not intercept self-invocation (enforced by an ArchUnit test).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Audited {
    String action();

    String entityType() default "CASE";

    /** Name of the parameter holding the entity id. Empty → fall back to the return value. */
    String entityIdParam() default "";
}
