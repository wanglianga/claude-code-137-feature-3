package com.community.medrecycle.repository;

import com.community.medrecycle.domain.ExceptionRecord;
import com.community.medrecycle.domain.enums.ExceptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExceptionRecordRepository extends JpaRepository<ExceptionRecord, Long> {
    Optional<ExceptionRecord> findByExceptionNo(String exceptionNo);
    List<ExceptionRecord> findByStatusOrderByCreatedAtDesc(ExceptionStatus status);
    List<ExceptionRecord> findByBatchId(Long batchId);
    List<ExceptionRecord> findByResidentIdOrderByCreatedAtDesc(Long residentId);
    long countByStatus(ExceptionStatus status);
}
