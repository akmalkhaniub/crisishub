package com.ir.common.event;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Published by {@code AuditAspect} to {@link Topics#AUDIT_EVENTS}; persisted by audit-service. */
public record AuditEvent(
        String eventId,
        String entityId,
        String entityType,
        String action,
        String outcome,
        String actor,
        String serviceName,
        Map<String, String> details,
        Instant at) {

    public static final String OK = "OK";
    public static final String FAILED = "FAILED";

    public static AuditEvent of(String action, String entityType, String entityId, String actor,
                                String serviceName, String outcome, Map<String, String> details) {
        return new AuditEvent(UUID.randomUUID().toString(), entityId, entityType, action, outcome,
                actor, serviceName, Map.copyOf(details), Instant.now());
    }
}
