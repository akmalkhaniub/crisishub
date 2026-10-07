package com.ir.cases.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** Append-only, user-facing case timeline (the compliance trail lives in audit-service). */
@Entity
@Table(name = "case_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CaseEvent {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    private UUID id;

    @JdbcTypeCode(SqlTypes.CHAR)
    private UUID caseId;

    private String eventType;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private CaseStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private CaseStatus toStatus;

    private String detail;
    private String actor;
    private Instant at;

    public CaseEvent(UUID caseId, String eventType, CaseStatus fromStatus, CaseStatus toStatus,
                     String detail, String actor, Instant at) {
        this.id = UUID.randomUUID();
        this.caseId = caseId;
        this.eventType = eventType;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.detail = detail;
        this.actor = actor;
        this.at = at;
    }
}
