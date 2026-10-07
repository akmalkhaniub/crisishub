package com.ir.audit.query;

import com.ir.audit.store.AuditRecordStore.AuditPage;
import com.ir.common.error.ApiException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** Exactly one of entityId / actor is required: both map to key queries, never table scans. */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditQueryService service;

    public AuditController(AuditQueryService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('auditor','admin')")
    public AuditPage query(@RequestParam(required = false) String entityId,
                           @RequestParam(required = false) String actor,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                           @RequestParam(defaultValue = "50") int limit,
                           @RequestParam(required = false) String nextToken) {
        if ((entityId == null) == (actor == null)) {
            throw ApiException.badRequest("invalid-query", "Provide exactly one of 'entityId' or 'actor'");
        }
        Instant start = from != null ? from : Instant.EPOCH;
        Instant end = to != null ? to : Instant.now();
        return service.query(entityId != null ? entityId : actor, actor != null, start, end,
                Math.clamp(limit, 1, 200), nextToken);
    }
}
