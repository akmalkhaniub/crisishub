package com.ir.cases.service;

import com.ir.cases.domain.CaseEvent;
import com.ir.cases.domain.CaseRecord;
import com.ir.cases.domain.CaseSeverity;
import com.ir.cases.domain.CaseStatus;
import com.ir.cases.domain.CaseType;
import com.ir.cases.repository.CaseEventRepository;
import com.ir.cases.repository.CaseRepository;
import com.ir.cases.service.ResponderClient.Reservation;
import com.ir.cases.service.ResponderClient.ResponderUnavailableException;
import com.ir.cases.statemachine.CaseStateMachine;
import com.ir.cases.statemachine.IllegalTransitionException;
import com.ir.common.aspect.Audited;
import com.ir.common.error.ApiException;
import com.ir.common.event.AssignmentEvent;
import com.ir.common.event.CaseDomainEvent;
import com.ir.common.event.Topics;
import com.ir.common.security.CurrentUser;
import com.ir.common.security.Roles;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.ir.cases.domain.CaseStatus.CANCELLED;
import static com.ir.cases.domain.CaseStatus.CLOSED;
import static com.ir.cases.domain.CaseStatus.IN_PROGRESS;
import static com.ir.cases.domain.CaseStatus.REPORTED;
import static com.ir.cases.domain.CaseStatus.RESOLVED;
import static com.ir.cases.domain.CaseStatus.TRIAGED;

/**
 * One public, {@code @Transactional} + {@code @Audited} method per lifecycle action. Role checks
 * happen in the controller; resource-level checks (ownership, "assigned responder") happen here.
 * All status changes go through the private {@link #applyTransition}, which is intentionally NOT
 * audited: it is only ever called via {@code this}, which bypasses the Spring proxy.
 */
@Service
public class CaseService {

    private final CaseRepository cases;
    private final CaseEventRepository caseEvents;
    private final OutboxWriter outbox;
    private final ResponderClient responders;
    private final CaseNumberGenerator caseNumbers;
    private final SlaCalculator sla;
    private final Clock clock;

    public CaseService(CaseRepository cases, CaseEventRepository caseEvents, OutboxWriter outbox,
                       ResponderClient responders, CaseNumberGenerator caseNumbers, SlaCalculator sla, Clock clock) {
        this.cases = cases;
        this.caseEvents = caseEvents;
        this.outbox = outbox;
        this.responders = responders;
        this.caseNumbers = caseNumbers;
        this.sla = sla;
        this.clock = clock;
    }

    public record NewCase(CaseType type, String title, String description, Double lat, Double lng,
                          CaseSeverity reportedSeverity) {
    }

    public record AssignOutcome(CaseView view, boolean pending) {
    }

    // ---------------------------------------------------------------- commands

    @Transactional
    @Audited(action = "CASE_REPORTED")
    public CaseView report(NewCase req, CurrentUser user) {
        Instant now = clock.instant();
        CaseRecord c = new CaseRecord();
        c.setId(UUID.randomUUID());
        c.setCaseNumber(caseNumbers.next());
        c.setType(req.type());
        c.setTitle(req.title());
        c.setDescription(req.description());
        c.setLat(req.lat());
        c.setLng(req.lng());
        c.setReportedSeverity(req.reportedSeverity());
        c.setMemberId(user.id());
        c.setStatus(REPORTED);
        c.setReportedAt(now);
        cases.save(c);
        record(c, "REPORTED", null, REPORTED, null, user.id());
        return CaseView.from(c);
    }

    @Transactional
    @Audited(action = "CASE_TRIAGED", entityIdParam = "caseId")
    public CaseView triage(UUID caseId, CaseSeverity severity, CurrentUser user) {
        CaseRecord c = load(caseId);
        applyTransition(c, TRIAGED, "TRIAGED", "severity=" + severity, user.id());
        c.setSeverity(severity);
        c.setTriagedAt(clock.instant());
        c.setSlaDueAt(sla.dueAt(c.getReportedAt(), severity));
        return CaseView.from(c);
    }

    /**
     * TRIAGED → IN_PROGRESS. If the responder system is unreachable the case stays TRIAGED with
     * {@code assignmentPending=true} (caller answers 202) instead of failing the operator's action.
     */
    @Transactional
    @Audited(action = "CASE_ASSIGNED", entityIdParam = "caseId")
    public AssignOutcome assign(UUID caseId, UUID responderId, CurrentUser user) {
        CaseRecord c = load(caseId);
        requireStatus(c, TRIAGED, IN_PROGRESS);
        Reservation r;
        try {
            r = responders.reserve(c.getId(), c.getType().requiredSkill(), responderId);
        } catch (ResponderUnavailableException e) {
            c.setAssignmentPending(true);
            applyTransition(c, TRIAGED, "ASSIGNMENT_PENDING", "responder system unavailable", user.id());
            return new AssignOutcome(CaseView.from(c), true);
        }
        c.setAssignmentPending(false);
        setAssignee(c, r);
        applyTransition(c, IN_PROGRESS, "ASSIGNED", "responder=" + r.responderId(), user.id());
        assignmentEvent(c, r.responderId(), AssignmentEvent.ASSIGNED);
        return new AssignOutcome(CaseView.from(c), false);
    }

    @Transactional
    @Audited(action = "CASE_REASSIGNED", entityIdParam = "caseId")
    public CaseView reassign(UUID caseId, UUID responderId, CurrentUser user) {
        CaseRecord c = load(caseId);
        requireStatus(c, IN_PROGRESS, IN_PROGRESS);
        UUID previous = c.getAssignedResponderId();
        if (responderId != null && responderId.equals(previous)) {
            throw ApiException.conflict("same-responder", "Case is already assigned to this responder");
        }
        Reservation r = responders.reserve(c.getId(), c.getType().requiredSkill(), responderId);
        releaseResponder(c, previous);
        setAssignee(c, r);
        applyTransition(c, IN_PROGRESS, "REASSIGNED", "from=" + previous + " to=" + r.responderId(), user.id());
        assignmentEvent(c, r.responderId(), AssignmentEvent.ASSIGNED);
        return CaseView.from(c);
    }

    @Transactional
    @Audited(action = "CASE_DECLINED", entityIdParam = "caseId")
    public CaseView decline(UUID caseId, CurrentUser user) {
        CaseRecord c = load(caseId);
        requireAssignedResponder(c, user);
        applyTransition(c, TRIAGED, "DECLINED", null, user.id());
        releaseResponder(c, c.getAssignedResponderId());
        clearAssignee(c);
        return CaseView.from(c);
    }

    @Transactional
    @Audited(action = "CASE_RESOLVED", entityIdParam = "caseId")
    public CaseView resolve(UUID caseId, String note, CurrentUser user) {
        CaseRecord c = load(caseId);
        if (!user.hasAny(Roles.OPERATOR, Roles.ADMIN)) {
            requireAssignedResponder(c, user);
        }
        applyTransition(c, RESOLVED, "RESOLVED", null, user.id());
        c.setResolvedAt(clock.instant());
        c.setResolutionNote(note);
        releaseResponder(c, c.getAssignedResponderId()); // keep assignee for reopen
        return CaseView.from(c);
    }

    /** RESOLVED → IN_PROGRESS with the same responder. SLA clock is not reset. */
    @Transactional
    @Audited(action = "CASE_REOPENED", entityIdParam = "caseId")
    public CaseView reopen(UUID caseId, CurrentUser user) {
        CaseRecord c = load(caseId);
        requireStatus(c, RESOLVED, IN_PROGRESS);
        Reservation r = responders.reserve(c.getId(), c.getType().requiredSkill(), c.getAssignedResponderId());
        setAssignee(c, r);
        applyTransition(c, IN_PROGRESS, "REOPENED", null, user.id());
        c.setResolvedAt(null);
        c.setReopenCount(c.getReopenCount() + 1);
        assignmentEvent(c, r.responderId(), AssignmentEvent.ASSIGNED);
        return CaseView.from(c);
    }

    @Transactional
    @Audited(action = "CASE_CLOSED", entityIdParam = "caseId")
    public CaseView close(UUID caseId, CurrentUser user) {
        CaseRecord c = load(caseId);
        applyTransition(c, CLOSED, "CLOSED", null, user.id());
        c.setClosedAt(clock.instant());
        return CaseView.from(c);
    }

    /** Operators may cancel REPORTED/TRIAGED cases; a member only their own case while REPORTED. */
    @Transactional
    @Audited(action = "CASE_CANCELLED", entityIdParam = "caseId")
    public CaseView cancel(UUID caseId, String reason, CurrentUser user) {
        CaseRecord c = load(caseId);
        if (!user.hasAny(Roles.OPERATOR, Roles.ADMIN)) {
            requireVisible(c, user);
            if (c.getStatus() != REPORTED) {
                throw ApiException.forbidden("Members can only cancel cases that are not yet triaged");
            }
        }
        applyTransition(c, CANCELLED, "CANCELLED", null, user.id());
        c.setCancelledAt(clock.instant());
        c.setCancelReason(reason);
        return CaseView.from(c);
    }

    /** Called by {@link SlaBreachScanner} (actor = system). One breach event per case. */
    @Transactional
    public int markSlaBreaches() {
        List<CaseRecord> breached = cases.findTop100ByStatusInAndSlaDueAtBeforeAndSlaBreachedAtIsNull(
                List.of(TRIAGED, IN_PROGRESS), clock.instant());
        for (CaseRecord c : breached) {
            c.setSlaBreachedAt(clock.instant());
            record(c, "SLA_BREACHED", c.getStatus(), c.getStatus(), "slaDueAt=" + c.getSlaDueAt(), "system");
        }
        return breached.size();
    }

    // ---------------------------------------------------------------- queries

    @Transactional(readOnly = true)
    public CaseView get(UUID caseId, CurrentUser user) {
        CaseRecord c = load(caseId);
        requireVisible(c, user);
        return CaseView.from(c);
    }

    @Transactional(readOnly = true)
    public List<CaseEvent> timeline(UUID caseId, CurrentUser user) {
        requireVisible(load(caseId), user);
        return caseEvents.findByCaseIdOrderByAtAsc(caseId);
    }

    /** Scope is forced into the query, never left to the client's filters. */
    @Transactional(readOnly = true)
    public Page<CaseView> list(CaseStatus status, CaseType type, CurrentUser user, Pageable pageable) {
        Specification<CaseRecord> spec = scope(user);
        if (status != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        }
        if (type != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("type"), type));
        }
        return cases.findAll(spec, pageable).map(CaseView::from);
    }

    static Specification<CaseRecord> scope(CurrentUser user) {
        if (seesAllCases(user)) {
            return (root, q, cb) -> cb.conjunction();
        }
        if (user.has(Roles.RESPONDER)) {
            return (root, q, cb) -> cb.equal(root.get("assignedResponderUserId"), user.id());
        }
        return (root, q, cb) -> cb.equal(root.get("memberId"), user.id());
    }

    // ---------------------------------------------------------------- internals

    private void applyTransition(CaseRecord c, CaseStatus target, String eventType, String detail, String actor) {
        CaseStatus from = c.getStatus();
        CaseStateMachine.check(from, target);
        c.setStatus(target);
        record(c, eventType, from, target, detail, actor);
    }

    private void record(CaseRecord c, String eventType, CaseStatus from, CaseStatus to, String detail, String actor) {
        Instant now = clock.instant();
        caseEvents.save(new CaseEvent(c.getId(), eventType, from, to, detail, actor, now));
        outbox.write(Topics.CASE_EVENTS, c.getId().toString(), new CaseDomainEvent(
                UUID.randomUUID().toString(), c.getId().toString(), c.getCaseNumber(), eventType,
                from == null ? null : from.name(), to.name(),
                c.getSeverity() == null ? null : c.getSeverity().name(), c.getMemberId(),
                c.getAssignedResponderId() == null ? null : c.getAssignedResponderId().toString(),
                actor, now, c.getSlaDueAt(), Map.of()));
    }

    private void assignmentEvent(CaseRecord c, UUID responderId, String action) {
        outbox.write(Topics.ASSIGNMENT_EVENTS, c.getId().toString(), new AssignmentEvent(
                UUID.randomUUID().toString(), c.getId().toString(), responderId.toString(), action, clock.instant()));
    }

    private void releaseResponder(CaseRecord c, UUID responderId) {
        if (responderId != null) {
            responders.release(responderId);
            assignmentEvent(c, responderId, AssignmentEvent.RELEASED);
        }
    }

    private void setAssignee(CaseRecord c, Reservation r) {
        c.setAssignedResponderId(r.responderId());
        c.setAssignedResponderUserId(r.userId());
        c.setAssignedAt(clock.instant());
    }

    private static void clearAssignee(CaseRecord c) {
        c.setAssignedResponderId(null);
        c.setAssignedResponderUserId(null);
        c.setAssignedAt(null);
    }

    /**
     * Several actions share the target IN_PROGRESS but differ in their source state (assign from
     * TRIAGED, reassign from IN_PROGRESS, reopen from RESOLVED), so the FSM alone can't tell them apart.
     */
    private static void requireStatus(CaseRecord c, CaseStatus expected, CaseStatus target) {
        if (c.getStatus() != expected) {
            throw new IllegalTransitionException(c.getStatus(), target);
        }
    }

    private CaseRecord load(UUID caseId) {
        return cases.findById(caseId).orElseThrow(() -> ApiException.notFound("Case"));
    }

    private static boolean seesAllCases(CurrentUser user) {
        return user.hasAny(Roles.OPERATOR, Roles.ADMIN, Roles.AUDITOR);
    }

    /** Out-of-scope reads are 404, not 403, so case ids can't be probed. */
    static void requireVisible(CaseRecord c, CurrentUser user) {
        boolean visible = seesAllCases(user)
                || (user.has(Roles.MEMBER) && Objects.equals(c.getMemberId(), user.id()))
                || (user.has(Roles.RESPONDER) && Objects.equals(c.getAssignedResponderUserId(), user.id()));
        if (!visible) {
            throw ApiException.notFound("Case");
        }
    }

    private static void requireAssignedResponder(CaseRecord c, CurrentUser user) {
        requireVisible(c, user);
        if (!Objects.equals(c.getAssignedResponderUserId(), user.id())) {
            throw ApiException.forbidden("Only the assigned responder may do this");
        }
    }
}
