package com.ir.cases.service;

import com.ir.cases.domain.OutboxEvent;
import com.ir.cases.repository.OutboxRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.UUID;

/** Must join the caller's transaction: the event exists if and only if the state change commits. */
@Component
public class OutboxWriter {

    private final OutboxRepository outbox;
    private final JsonMapper json;
    private final Clock clock;

    public OutboxWriter(OutboxRepository outbox, JsonMapper json, Clock clock) {
        this.outbox = outbox;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void write(String topic, String key, Object payload) {
        outbox.save(new OutboxEvent(UUID.randomUUID(), topic, key, json.writeValueAsString(payload), clock.instant()));
    }
}
