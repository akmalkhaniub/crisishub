package com.ir.cases.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "case_record")
@Getter
@Setter
public class CaseRecord {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String caseNumber;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false)
    private CaseType type;

    /** Member's self-assessment at report time; informs the operator queue only. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private CaseSeverity reportedSeverity;

    /** Authoritative severity, set at triage. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private CaseSeverity severity;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false)
    private CaseStatus status;

    private boolean assignmentPending;

    @Column(nullable = false)
    private String memberId;

    @Column(nullable = false)
    private String title;

    private String description;

    // Incident location is PII: never logged or put into events.
    private Double lat;
    private Double lng;

    @JdbcTypeCode(SqlTypes.CHAR)
    private UUID assignedResponderId;

    /** Keycloak sub of the assigned responder, for "only the assigned responder may resolve". */
    private String assignedResponderUserId;

    private Instant reportedAt;
    private Instant triagedAt;
    private Instant assignedAt;
    private Instant resolvedAt;
    private Instant closedAt;
    private Instant cancelledAt;
    private Instant slaDueAt;
    private Instant slaBreachedAt;

    private int reopenCount;
    private String cancelReason;
    private String resolutionNote;

    @Version
    private Long version;

    private Instant createdAt;
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
