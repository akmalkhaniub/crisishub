package com.ir.cases.service;

import com.ir.cases.domain.CaseEvent;
import com.ir.cases.domain.CaseRecord;
import com.ir.cases.domain.CaseSeverity;
import com.ir.cases.domain.CaseStatus;
import com.ir.cases.domain.CaseType;
import com.ir.cases.repository.CaseEventRepository;
import com.ir.cases.repository.CaseRepository;
import com.ir.cases.service.CaseService.AssignOutcome;
import com.ir.cases.service.CaseService.NewCase;
import com.ir.cases.service.ResponderClient.Reservation;
import com.ir.cases.service.ResponderClient.ResponderUnavailableException;
import com.ir.cases.statemachine.IllegalTransitionException;
import com.ir.common.error.ApiException;
import com.ir.common.event.AssignmentEvent;
import com.ir.common.event.CaseDomainEvent;
import com.ir.common.event.Topics;
import com.ir.common.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CaseServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
    private static final CurrentUser MEMBER = new CurrentUser("member-1", Set.of("member"));
    private static final CurrentUser OTHER_MEMBER = new CurrentUser("member-2", Set.of("member"));
    private static final CurrentUser OPERATOR = new CurrentUser("op-1", Set.of("operator"));
    private static final CurrentUser RESP1 = new CurrentUser("resp-user-1", Set.of("responder"));
    private static final CurrentUser RESP2 = new CurrentUser("resp-user-2", Set.of("responder"));
    private static final UUID RESPONDER_1 = UUID.randomUUID();
    private static final UUID RESPONDER_2 = UUID.randomUUID();

    private final Map<UUID, CaseRecord> db = new HashMap<>();
    private final CaseRepository cases = mock(CaseRepository.class);
    private final CaseEventRepository events = mock(CaseEventRepository.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final ResponderClient responders = mock(ResponderClient.class);
    private final CaseNumberGenerator numbers = mock(CaseNumberGenerator.class);
    private CaseService service;

    @BeforeEach
    void setUp() {
        when(cases.save(any())).thenAnswer(i -> {
            CaseRecord c = i.getArgument(0);
            db.put(c.getId(), c);
            return c;
        });
        when(cases.findById(any())).thenAnswer(i -> Optional.ofNullable(db.get(i.<UUID>getArgument(0))));
        when(numbers.next()).thenReturn("CR-2026-000001");
        when(responders.reserve(any(), anyString(), isNull())).thenReturn(new Reservation(RESPONDER_1, RESP1.id()));
        when(responders.reserve(any(), anyString(), eq(RESPONDER_1))).thenReturn(new Reservation(RESPONDER_1, RESP1.id()));
        when(responders.reserve(any(), anyString(), eq(RESPONDER_2))).thenReturn(new Reservation(RESPONDER_2, RESP2.id()));
        service = new CaseService(cases, events, outbox, responders, numbers, new SlaCalculator(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void reportCreatesReportedCaseOwnedByCallerAndEmitsEvent() {
        CaseView v = report();

        assertThat(v.status()).isEqualTo(CaseStatus.REPORTED);
        assertThat(v.memberId()).isEqualTo(MEMBER.id());
        assertThat(v.caseNumber()).isEqualTo("CR-2026-000001");
        verify(events).save(any(CaseEvent.class));
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outbox).write(eq(Topics.CASE_EVENTS), eq(v.id().toString()), payload.capture());
        assertThat(payload.getValue()).isInstanceOfSatisfying(CaseDomainEvent.class,
                e -> assertThat(e.eventType()).isEqualTo("REPORTED"));
    }

    @Test
    void triageSetsSeverityAndSlaFromReportTime() {
        CaseView v = service.triage(report().id(), CaseSeverity.CRITICAL, OPERATOR);

        assertThat(v.status()).isEqualTo(CaseStatus.TRIAGED);
        assertThat(v.slaDueAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
    }

    @Test
    void cannotAssignBeforeTriage() {
        UUID id = report().id();

        assertThatThrownBy(() -> service.assign(id, null, OPERATOR)).isInstanceOf(IllegalTransitionException.class);
        verify(responders, never()).reserve(any(), anyString(), any());
    }

    @Test
    void assignMovesToInProgressAndEmitsAssignmentEvent() {
        UUID id = triaged();

        AssignOutcome out = service.assign(id, null, OPERATOR);

        assertThat(out.pending()).isFalse();
        assertThat(out.view().status()).isEqualTo(CaseStatus.IN_PROGRESS);
        assertThat(out.view().assignedResponderId()).isEqualTo(RESPONDER_1);
        verify(outbox).write(eq(Topics.ASSIGNMENT_EVENTS), eq(id.toString()), any(AssignmentEvent.class));
    }

    @Test
    void assignWhenResponderSystemDownKeepsTriagedAndPending() {
        UUID id = triaged();
        when(responders.reserve(any(), anyString(), any()))
                .thenThrow(new ResponderUnavailableException("down", null));

        AssignOutcome out = service.assign(id, null, OPERATOR);

        assertThat(out.pending()).isTrue();
        assertThat(out.view().status()).isEqualTo(CaseStatus.TRIAGED);
        assertThat(out.view().assignmentPending()).isTrue();
    }

    @Test
    void onlyTheAssignedResponderMayResolve() {
        UUID id = inProgress();

        assertThatThrownBy(() -> service.resolve(id, "done", RESP2))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));

        CaseView v = service.resolve(id, "done", RESP1);
        assertThat(v.status()).isEqualTo(CaseStatus.RESOLVED);
        verify(responders).release(RESPONDER_1);
    }

    @Test
    void operatorMayResolveOnBehalfOfResponder() {
        assertThat(service.resolve(inProgress(), "remote fix", OPERATOR).status()).isEqualTo(CaseStatus.RESOLVED);
    }

    @Test
    void reopenKeepsSlaAndIncrementsCountAndReservesSameResponder() {
        UUID id = inProgress();
        Instant sla = db.get(id).getSlaDueAt();
        service.resolve(id, "done", RESP1);

        CaseView v = service.reopen(id, OPERATOR);

        assertThat(v.status()).isEqualTo(CaseStatus.IN_PROGRESS);
        assertThat(v.slaDueAt()).isEqualTo(sla);
        assertThat(v.reopenCount()).isEqualTo(1);
        assertThat(v.resolvedAt()).isNull();
        verify(responders, atLeastOnce()).reserve(id, "MEDICAL", RESPONDER_1);
    }

    @Test
    void declineReturnsCaseToTriagedAndReleasesResponder() {
        UUID id = inProgress();

        CaseView v = service.decline(id, RESP1);

        assertThat(v.status()).isEqualTo(CaseStatus.TRIAGED);
        assertThat(v.assignedResponderId()).isNull();
        verify(responders).release(RESPONDER_1);
    }

    @Test
    void reassignToSameResponderIsRejected() {
        UUID id = inProgress();

        assertThatThrownBy(() -> service.reassign(id, RESPONDER_1, OPERATOR))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("same-responder"));
    }

    @Test
    void reassignReleasesPreviousResponder() {
        UUID id = inProgress();

        CaseView v = service.reassign(id, RESPONDER_2, OPERATOR);

        assertThat(v.assignedResponderId()).isEqualTo(RESPONDER_2);
        verify(responders).release(RESPONDER_1);
    }

    @Test
    void memberCanCancelOwnCaseOnlyWhileReported() {
        UUID own = report().id();
        assertThat(service.cancel(own, "false alarm", MEMBER).status()).isEqualTo(CaseStatus.CANCELLED);

        UUID triaged = triaged();
        assertThatThrownBy(() -> service.cancel(triaged, "changed mind", MEMBER))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void memberCannotSeeOrCancelSomeoneElsesCase() {
        UUID id = report().id();

        assertThatThrownBy(() -> service.get(id, OTHER_MEMBER))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.cancel(id, "x", OTHER_MEMBER))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void closedCaseRejectsFurtherTransitions() {
        UUID id = inProgress();
        service.resolve(id, "done", RESP1);
        service.close(id, OPERATOR);

        assertThatThrownBy(() -> service.reopen(id, OPERATOR)).isInstanceOf(IllegalTransitionException.class);
        assertThatThrownBy(() -> service.cancel(id, "x", OPERATOR)).isInstanceOf(IllegalTransitionException.class);
    }

    private CaseView report() {
        return service.report(new NewCase(CaseType.MEDICAL, "Fall injury", "desc", 1.0, 2.0, CaseSeverity.HIGH), MEMBER);
    }

    private UUID triaged() {
        UUID id = report().id();
        service.triage(id, CaseSeverity.HIGH, OPERATOR);
        return id;
    }

    private UUID inProgress() {
        UUID id = triaged();
        service.assign(id, null, OPERATOR);
        return id;
    }
}
