package com.ir.cases.repository;

import com.ir.cases.domain.StubResponder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StubResponderRepository extends JpaRepository<StubResponder, UUID> {
    List<StubResponder> findByAvailableTrue();
}
