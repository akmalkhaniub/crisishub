package com.ir.common.aspect;

import com.ir.common.event.AuditEvent;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LoggingAuditEventPublisher implements AuditEventPublisher {
    @Override
    public void publish(AuditEvent e) {
        log.info("AUDIT service={} actor={} action={} entity={}:{} outcome={} details={}",
                e.serviceName(), e.actor(), e.action(), e.entityType(), e.entityId(), e.outcome(), e.details());
    }
}
