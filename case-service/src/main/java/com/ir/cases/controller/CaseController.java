package com.ir.cases.controller;

import com.ir.cases.domain.CaseEvent;
import com.ir.cases.domain.CaseSeverity;
import com.ir.cases.domain.CaseStatus;
import com.ir.cases.domain.CaseType;
import com.ir.cases.service.CaseService;
import com.ir.cases.service.CaseService.AssignOutcome;
import com.ir.cases.service.CaseService.NewCase;
import com.ir.cases.service.CaseView;
import com.ir.common.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Role gates here (spec §7 matrix); ownership / assigned-responder checks in {@link CaseService}. */
@RestController
@RequestMapping("/api/cases")
public class CaseController {

    private static final String OPERATOR = "hasAnyRole('operator','admin')";

    private final CaseService service;

    public CaseController(CaseService service) {
        this.service = service;
    }

    public record ReportRequest(
            @NotNull CaseType type,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 4000) String description,
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng,
            CaseSeverity reportedSeverity) {
    }

    public record TriageRequest(@NotNull CaseSeverity severity) {
    }

    public record AssignRequest(UUID responderId) {
    }

    public record ResolveRequest(@NotBlank @Size(max = 4000) String note) {
    }

    public record CancelRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
        static <T> PageResponse<T> of(Page<T> p) {
            return new PageResponse<>(p.getContent(), p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
        }
    }

    public record TimelineEntry(String eventType, CaseStatus fromStatus, CaseStatus toStatus, String detail,
                                String actor, Instant at) {
        static TimelineEntry of(CaseEvent e) {
            return new TimelineEntry(e.getEventType(), e.getFromStatus(), e.getToStatus(), e.getDetail(), e.getActor(), e.getAt());
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('member')")
    public CaseView report(@Valid @RequestBody ReportRequest r) {
        return service.report(new NewCase(r.type(), r.title(), r.description(), r.lat(), r.lng(), r.reportedSeverity()),
                CurrentUser.fromSecurityContext());
    }

    @GetMapping
    public PageResponse<CaseView> list(@RequestParam(required = false) CaseStatus status,
                                       @RequestParam(required = false) CaseType type,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100),
                Sort.by(Sort.Direction.DESC, "reportedAt"));
        return PageResponse.of(service.list(status, type, CurrentUser.fromSecurityContext(), pageable));
    }

    @GetMapping("/{id}")
    public CaseView get(@PathVariable UUID id) {
        return service.get(id, CurrentUser.fromSecurityContext());
    }

    @GetMapping("/{id}/timeline")
    public List<TimelineEntry> timeline(@PathVariable UUID id) {
        return service.timeline(id, CurrentUser.fromSecurityContext()).stream().map(TimelineEntry::of).toList();
    }

    @PostMapping("/{id}/triage")
    @PreAuthorize(OPERATOR)
    public CaseView triage(@PathVariable UUID id, @Valid @RequestBody TriageRequest r) {
        return service.triage(id, r.severity(), CurrentUser.fromSecurityContext());
    }

    /** 200 when assigned; 202 when the responder system is down and assignment was queued. */
    @PostMapping("/{id}/assign")
    @PreAuthorize(OPERATOR)
    public ResponseEntity<CaseView> assign(@PathVariable UUID id, @RequestBody(required = false) AssignRequest r) {
        AssignOutcome outcome = service.assign(id, r == null ? null : r.responderId(), CurrentUser.fromSecurityContext());
        return ResponseEntity.status(outcome.pending() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(outcome.view());
    }

    @PostMapping("/{id}/reassign")
    @PreAuthorize(OPERATOR)
    public CaseView reassign(@PathVariable UUID id, @RequestBody(required = false) AssignRequest r) {
        return service.reassign(id, r == null ? null : r.responderId(), CurrentUser.fromSecurityContext());
    }

    @PostMapping("/{id}/decline")
    @PreAuthorize("hasRole('responder')")
    public CaseView decline(@PathVariable UUID id) {
        return service.decline(id, CurrentUser.fromSecurityContext());
    }

    @PostMapping("/{id}/resolve")
    @PreAuthorize("hasAnyRole('responder','operator','admin')")
    public CaseView resolve(@PathVariable UUID id, @Valid @RequestBody ResolveRequest r) {
        return service.resolve(id, r.note(), CurrentUser.fromSecurityContext());
    }

    @PostMapping("/{id}/reopen")
    @PreAuthorize(OPERATOR)
    public CaseView reopen(@PathVariable UUID id) {
        return service.reopen(id, CurrentUser.fromSecurityContext());
    }

    @PostMapping("/{id}/close")
    @PreAuthorize(OPERATOR)
    public CaseView close(@PathVariable UUID id) {
        return service.close(id, CurrentUser.fromSecurityContext());
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('member','operator','admin')")
    public CaseView cancel(@PathVariable UUID id, @Valid @RequestBody CancelRequest r) {
        return service.cancel(id, r.reason(), CurrentUser.fromSecurityContext());
    }
}
