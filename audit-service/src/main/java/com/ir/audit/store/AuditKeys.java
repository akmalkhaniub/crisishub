package com.ir.audit.store;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Sort key = {@code <fixed-width UTC timestamp>#<eventId>}.
 * <p>
 * Fixed width matters: {@code Instant.toString()} drops trailing zero fractions, and because
 * {@code '.'} sorts before {@code 'Z'}, "12:00:00.5Z" would sort BEFORE "12:00:00Z" as a string.
 * Both parts come from the producer, so a redelivered event maps to the same key (idempotent put).
 */
public final class AuditKeys {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    private AuditKeys() {
    }

    public static String sortKey(Instant at, String eventId) {
        return TS.format(at) + "#" + eventId;
    }

    /** Inclusive lower bound for range queries. */
    public static String lowerBound(Instant from) {
        return TS.format(from);
    }

    /** Inclusive upper bound: '~' sorts after '#' and every UUID character. */
    public static String upperBound(Instant to) {
        return TS.format(to) + "~";
    }
}
