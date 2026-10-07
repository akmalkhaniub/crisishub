package com.ir.audit.consumer;

import com.ir.audit.store.AuditRecordStore;
import com.ir.common.event.AuditEvent;
import com.ir.common.event.Topics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * At-least-once consumer. Idempotent because the DynamoDB key is derived from producer-side
 * fields and the put is conditional. Poison messages go to {@code audit.events-dlt} after 3 retries
 * (error handler from {@code common}).
 */
@Component
public class AuditEventListener {

    private final AuditRecordStore store;
    private final JsonMapper json;

    public AuditEventListener(AuditRecordStore store, JsonMapper json) {
        this.store = store;
        this.json = json;
    }

    @KafkaListener(topics = Topics.AUDIT_EVENTS, groupId = "audit-service")
    public void onAuditEvent(String payload) {
        store.append(json.readValue(payload, AuditEvent.class));
    }
}
