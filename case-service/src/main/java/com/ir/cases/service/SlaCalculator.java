package com.ir.cases.service;

import com.ir.cases.domain.CaseSeverity;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/** SLA is measured from {@code reportedAt}, so reopening a case never resets the clock. */
@Component
public class SlaCalculator {

    public Duration window(CaseSeverity severity) {
        return switch (severity) {
            case CRITICAL -> Duration.ofHours(1);
            case HIGH -> Duration.ofHours(4);
            case MEDIUM -> Duration.ofHours(24);
            case LOW -> Duration.ofHours(72);
        };
    }

    public Instant dueAt(Instant reportedAt, CaseSeverity severity) {
        return reportedAt.plus(window(severity));
    }
}
