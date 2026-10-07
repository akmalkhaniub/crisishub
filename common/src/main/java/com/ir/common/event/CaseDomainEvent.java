package com.ir.common.event;

import java.time.Instant;
import java.util.Map;

public record CaseDomainEvent(
        String eventId,
        String caseId,
        String caseNumber,
        String eventType,
        String fromStatus,
        String status,
        String severity,
        String memberId,
        String assignedResponderId,
        String actor,
        Instant at,
        Instant slaDueAt,
        Map<String, String> data) {
}
