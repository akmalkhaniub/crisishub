package com.ir.common.aspect;

import com.ir.common.event.AuditEvent;

/** Implementations must never throw: an audit failure must not fail the business call. */
public interface AuditEventPublisher {
    void publish(AuditEvent event);
}
