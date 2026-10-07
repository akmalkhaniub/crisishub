package com.ir.cases.repository;

import com.ir.cases.domain.CaseEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CaseEventRepository extends JpaRepository<CaseEvent, UUID> {
    List<CaseEvent> findByCaseIdOrderByAtAsc(UUID caseId);
}
