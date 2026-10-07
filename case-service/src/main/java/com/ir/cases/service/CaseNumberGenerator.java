package com.ir.cases.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Year;

/**
 * {@code CR-<year>-<6 digits>} from a per-year counter row. MySQL's
 * {@code LAST_INSERT_ID(expr)} makes increment-and-read atomic and safe across instances
 * (the value is per-connection, and both statements run in the caller's transaction).
 */
@Component
public class CaseNumberGenerator {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public CaseNumberGenerator(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public String next() {
        int year = Year.now(clock).getValue();
        jdbc.update("""
                INSERT INTO case_number_seq (seq_year, next_val) VALUES (?, LAST_INSERT_ID(1))
                ON DUPLICATE KEY UPDATE next_val = LAST_INSERT_ID(next_val + 1)""", year);
        Long value = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return "CR-%d-%06d".formatted(year, value);
    }
}
