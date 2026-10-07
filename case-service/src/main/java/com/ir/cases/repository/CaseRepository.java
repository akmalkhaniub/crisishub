package com.ir.cases.repository;

import com.ir.cases.domain.CaseRecord;
import com.ir.cases.domain.CaseStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CaseRepository extends JpaRepository<CaseRecord, UUID>, JpaSpecificationExecutor<CaseRecord> {

    List<CaseRecord> findTop100ByStatusInAndSlaDueAtBeforeAndSlaBreachedAtIsNull(
            Collection<CaseStatus> statuses, Instant now);
}
