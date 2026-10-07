package com.ir.common.event;

import java.time.Instant;

public record AssignmentEvent(String eventId, String caseId, String responderId, String action, Instant at) {
    public static final String ASSIGNED = "ASSIGNED";
    public static final String RELEASED = "RELEASED";
}
