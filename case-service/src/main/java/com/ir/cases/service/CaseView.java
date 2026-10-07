package com.ir.cases.service;

import com.ir.cases.domain.CaseRecord;
import com.ir.cases.domain.CaseSeverity;
import com.ir.cases.domain.CaseStatus;
import com.ir.cases.domain.CaseType;
import com.ir.common.aspect.AuditIdentifiable;

import java.time.Instant;
import java.util.UUID;

public record CaseView(
        UUID id,
        String caseNumber,
        CaseType type,
        CaseSeverity reportedSeverity,
        CaseSeverity severity,
        CaseStatus status,
        boolean assignmentPending,
        String memberId,
        String title,
        String description,
        Double lat,
        Double lng,
        UUID assignedResponderId,
        Instant reportedAt,
        Instant triagedAt,
        Instant assignedAt,
        Instant resolvedAt,
        Instant closedAt,
        Instant cancelledAt,
        Instant slaDueAt,
        Instant slaBreachedAt,
        int reopenCount,
        Long version) implements AuditIdentifiable {

    public static CaseView from(CaseRecord c) {
        return new CaseView(c.getId(), c.getCaseNumber(), c.getType(), c.getReportedSeverity(), c.getSeverity(),
                c.getStatus(), c.isAssignmentPending(), c.getMemberId(), c.getTitle(), c.getDescription(),
                c.getLat(), c.getLng(), c.getAssignedResponderId(), c.getReportedAt(), c.getTriagedAt(),
                c.getAssignedAt(), c.getResolvedAt(), c.getClosedAt(), c.getCancelledAt(), c.getSlaDueAt(),
                c.getSlaBreachedAt(), c.getReopenCount(), c.getVersion());
    }

    @Override
    public String auditEntityId() {
        return id.toString();
    }
}
