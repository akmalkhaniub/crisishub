package com.ir.cases.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Arrays;
import java.util.UUID;

/**
 * Minimum-slice stand-in for responder-service (spec §12): a seeded table inside case-service.
 * Replaced by an HTTP client to responder-service in build step 6.
 */
@Entity
@Table(name = "responder_stub")
@Getter
@Setter
public class StubResponder {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    private UUID id;

    private String userId;
    private String name;
    /** Comma-separated skill names, e.g. "MEDICAL,EVACUATION". */
    private String skills;
    private boolean available;
    private int activeCaseCount;
    private int maxConcurrentCases;

    @Version
    private Long version;

    public boolean hasSkill(String skill) {
        return Arrays.asList(skills.split(",")).contains(skill);
    }

    public boolean hasCapacity() {
        return available && activeCaseCount < maxConcurrentCases;
    }
}
