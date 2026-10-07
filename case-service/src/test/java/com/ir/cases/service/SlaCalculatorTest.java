package com.ir.cases.service;

import com.ir.cases.domain.CaseSeverity;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SlaCalculatorTest {

    private final SlaCalculator sla = new SlaCalculator();

    @ParameterizedTest
    @CsvSource({"CRITICAL,1", "HIGH,4", "MEDIUM,24", "LOW,72"})
    void dueAtIsReportedAtPlusSeverityWindow(CaseSeverity severity, long hours) {
        Instant reported = Instant.parse("2026-10-07T10:00:00Z");

        assertThat(sla.dueAt(reported, severity)).isEqualTo(reported.plus(Duration.ofHours(hours)));
    }
}
