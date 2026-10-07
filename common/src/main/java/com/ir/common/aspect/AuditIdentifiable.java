package com.ir.common.aspect;

/** Return types implement this so {@link AuditAspect} can audit creations (no id parameter yet). */
public interface AuditIdentifiable {
    String auditEntityId();
}
