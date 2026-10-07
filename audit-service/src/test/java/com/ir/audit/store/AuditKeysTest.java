package com.ir.audit.store;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuditKeysTest {

    @Test
    void sortKeysOrderChronologicallyAsStrings() {
        // Instant.toString would give "…00Z" and "…00.500Z": '.' < 'Z' breaks lexical order.
        Instant whole = Instant.parse("2026-10-07T12:00:00Z");
        Instant half = Instant.parse("2026-10-07T12:00:00.500Z");
        Instant later = Instant.parse("2026-10-07T12:00:01Z");

        List<String> keys = List.of(AuditKeys.sortKey(later, "c"), AuditKeys.sortKey(whole, "a"), AuditKeys.sortKey(half, "b"));

        assertThat(keys.stream().sorted().toList()).containsExactly(
                AuditKeys.sortKey(whole, "a"), AuditKeys.sortKey(half, "b"), AuditKeys.sortKey(later, "c"));
    }

    @Test
    void sameEventAlwaysMapsToSameKey() {
        Instant at = Instant.parse("2026-10-07T12:00:00.123456Z");

        assertThat(AuditKeys.sortKey(at, "evt-1")).isEqualTo("2026-10-07T12:00:00.123456Z#evt-1")
                .isEqualTo(AuditKeys.sortKey(at, "evt-1"));
    }

    @Test
    void boundsAreInclusive() {
        Instant at = Instant.parse("2026-10-07T12:00:00Z");
        String key = AuditKeys.sortKey(at, "f47ac10b-58cc-4372-a567-0e02b2c3d479");

        assertThat(key.compareTo(AuditKeys.lowerBound(at))).isGreaterThanOrEqualTo(0);
        assertThat(key.compareTo(AuditKeys.upperBound(at))).isLessThan(0);
    }
}
