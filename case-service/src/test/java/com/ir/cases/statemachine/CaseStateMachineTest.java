package com.ir.cases.statemachine;

import com.ir.cases.domain.CaseStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static com.ir.cases.domain.CaseStatus.CANCELLED;
import static com.ir.cases.domain.CaseStatus.CLOSED;
import static com.ir.cases.domain.CaseStatus.IN_PROGRESS;
import static com.ir.cases.domain.CaseStatus.REPORTED;
import static com.ir.cases.domain.CaseStatus.RESOLVED;
import static com.ir.cases.domain.CaseStatus.TRIAGED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaseStateMachineTest {

    /** Spec §4 transition table, written out independently of the implementation. */
    private static final Map<CaseStatus, Set<CaseStatus>> SPEC = Map.of(
            REPORTED, EnumSet.of(TRIAGED, CANCELLED),
            TRIAGED, EnumSet.of(TRIAGED, IN_PROGRESS, CANCELLED),
            IN_PROGRESS, EnumSet.of(IN_PROGRESS, TRIAGED, RESOLVED),
            RESOLVED, EnumSet.of(IN_PROGRESS, CLOSED),
            CLOSED, EnumSet.noneOf(CaseStatus.class),
            CANCELLED, EnumSet.noneOf(CaseStatus.class));

    static Stream<Arguments> allPairs() {
        return Arrays.stream(CaseStatus.values())
                .flatMap(from -> Arrays.stream(CaseStatus.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("allPairs")
    void exactlyTheSpecifiedEdgesAreAllowed(CaseStatus from, CaseStatus to) {
        boolean expected = SPEC.get(from).contains(to);

        assertThat(CaseStateMachine.isAllowed(from, to)).isEqualTo(expected);
        if (expected) {
            CaseStateMachine.check(from, to);
        } else {
            assertThatThrownBy(() -> CaseStateMachine.check(from, to)).isInstanceOf(IllegalTransitionException.class);
        }
    }

    @ParameterizedTest
    @EnumSource(value = CaseStatus.class, names = {"CLOSED", "CANCELLED"})
    void terminalStatesHaveNoExits(CaseStatus terminal) {
        assertThat(CaseStatus.values()).noneMatch(to -> CaseStateMachine.isAllowed(terminal, to));
    }

    @Test
    void noPathReachesWorkWithoutTriage() {
        // From REPORTED the only non-terminal successor is TRIAGED.
        assertThat(CaseStateMachine.isAllowed(REPORTED, IN_PROGRESS)).isFalse();
        assertThat(CaseStateMachine.isAllowed(REPORTED, RESOLVED)).isFalse();
        assertThat(CaseStateMachine.isAllowed(REPORTED, CLOSED)).isFalse();
    }
}
