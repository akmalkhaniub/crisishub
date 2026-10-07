package com.ir.common.aspect;

import com.ir.common.event.AuditEvent;
import com.ir.common.event.Topics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
public class KafkaAuditEventPublisher implements AuditEventPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;

    public KafkaAuditEventPublisher(KafkaTemplate<String, String> kafka, JsonMapper json) {
        this.kafka = kafka;
        this.json = json;
    }

    @Override
    public void publish(AuditEvent event) {
        try {
            kafka.send(Topics.AUDIT_EVENTS, event.entityId(), json.writeValueAsString(event))
                    .whenComplete((r, ex) -> {
                        if (ex != null) {
                            log.error("AUDIT event {} not delivered", event.eventId(), ex);
                        }
                    });
        } catch (RuntimeException e) {
            log.error("AUDIT event {} not sent", event.eventId(), e);
        }
    }
}
