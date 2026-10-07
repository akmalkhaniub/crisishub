package com.ir.audit.query;

import com.ir.audit.store.AuditRecordStore;
import com.ir.audit.store.AuditRecordStore.AuditPage;
import com.ir.common.aspect.Audited;
import com.ir.common.error.ApiException;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class AuditQueryService {

    private final AuditRecordStore store;

    public AuditQueryService(AuditRecordStore store) {
        this.store = store;
    }

    /** Reading the audit trail is itself audited ("who looked at what"). */
    @Audited(action = "AUDIT_QUERIED", entityType = "AUDIT", entityIdParam = "subject")
    public AuditPage query(String subject, boolean byActor, Instant from, Instant to, int limit, String nextToken) {
        if (from.isAfter(to)) {
            throw ApiException.badRequest("invalid-range", "'from' must not be after 'to'");
        }
        return byActor
                ? store.byActor(subject, from, to, limit, nextToken)
                : store.byEntity(subject, from, to, limit, nextToken);
    }
}
