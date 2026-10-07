package com.ir.cases.service;

import com.ir.cases.domain.OutboxEvent;
import com.ir.cases.repository.OutboxRepository;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.concurrent.TimeUnit;

/**
 * Publishes committed outbox rows in creation order. A crash after send but before
 * {@code markPublished} re-sends the row; consumers dedupe by {@code eventId}.
 */
@Slf4j
@Component
public class OutboxRelay {

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final Clock clock;

    public OutboxRelay(OutboxRepository outbox, KafkaTemplate<String, String> kafka, Clock clock) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${ir.outbox.poll-ms:1000}")
    @SchedulerLock(name = "outbox-relay", lockAtMostFor = "PT30S")
    @Transactional
    public void relay() {
        for (OutboxEvent e : outbox.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()) {
            try {
                kafka.send(e.getTopic(), e.getMsgKey(), e.getPayloadJson()).get(5, TimeUnit.SECONDS);
            } catch (Exception ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.warn("Outbox relay stopped at {} (will retry): {}", e.getId(), ex.toString());
                return; // keep per-key ordering: don't skip ahead
            }
            e.markPublished(clock.instant());
        }
    }
}
