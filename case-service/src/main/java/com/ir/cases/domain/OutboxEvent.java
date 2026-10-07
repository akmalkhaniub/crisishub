package com.ir.cases.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** Transactional outbox row, written in the same DB transaction as the state change. */
@Entity
@Table(name = "outbox_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    private UUID id;

    private String topic;
    private String msgKey;
    private String payloadJson;
    private Instant createdAt;
    private Instant publishedAt;

    public OutboxEvent(UUID id, String topic, String msgKey, String payloadJson, Instant createdAt) {
        this.id = id;
        this.topic = topic;
        this.msgKey = msgKey;
        this.payloadJson = payloadJson;
        this.createdAt = createdAt;
    }

    public void markPublished(Instant at) {
        this.publishedAt = at;
    }
}
