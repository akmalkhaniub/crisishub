package com.ir.cases.statemachine;

import com.ir.cases.domain.CaseStatus;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.ir.cases.domain.CaseStatus.CANCELLED;
import static com.ir.cases.domain.CaseStatus.CLOSED;
import static com.ir.cases.domain.CaseStatus.IN_PROGRESS;
import static com.ir.cases.domain.CaseStatus.REPORTED;
import static com.ir.cases.domain.CaseStatus.RESOLVED;
import static com.ir.cases.domain.CaseStatus.TRIAGED;

/**
 * Allowed status edges (spec §4). Pure and framework-free; who may take an edge, and its
 * preconditions, are enforced by {@code CaseService}. No edge skips TRIAGED.
 */
public final class CaseStateMachine {

    private static final Map<CaseStatus, Set<CaseStatus>> ALLOWED = new EnumMap<>(CaseStatus.class);

    static {
        ALLOWED.put(REPORTED, EnumSet.of(TRIAGED, CANCELLED));
        ALLOWED.put(TRIAGED, EnumSet.of(TRIAGED, IN_PROGRESS, CANCELLED));    // TRIAGED→TRIAGED: assignment pending
        ALLOWED.put(IN_PROGRESS, EnumSet.of(IN_PROGRESS, TRIAGED, RESOLVED)); // reassign, decline, resolve
        ALLOWED.put(RESOLVED, EnumSet.of(IN_PROGRESS, CLOSED));               // reopen, close
        ALLOWED.put(CLOSED, EnumSet.noneOf(CaseStatus.class));
        ALLOWED.put(CANCELLED, EnumSet.noneOf(CaseStatus.class));
    }

    private CaseStateMachine() {
    }

    public static boolean isAllowed(CaseStatus from, CaseStatus to) {
        return ALLOWED.get(from).contains(to);
    }

    public static void check(CaseStatus from, CaseStatus to) {
        if (!isAllowed(from, to)) {
            throw new IllegalTransitionException(from, to);
        }
    }
}
