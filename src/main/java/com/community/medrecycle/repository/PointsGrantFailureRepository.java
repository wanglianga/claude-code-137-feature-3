package com.community.medrecycle.repository;

import com.community.medrecycle.domain.PointsGrantFailure;
import com.community.medrecycle.domain.enums.GrantFailureStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PointsGrantFailureRepository extends JpaRepository<PointsGrantFailure, Long> {
    Optional<PointsGrantFailure> findByFailureNo(String failureNo);
    List<PointsGrantFailure> findByStatusOrderByCreatedAtDesc(GrantFailureStatus status);
    List<PointsGrantFailure> findByResidentIdOrderByCreatedAtDesc(Long residentId);
    Optional<PointsGrantFailure> findByBagNoAndStatus(String bagNo, GrantFailureStatus status);
    long countByStatus(GrantFailureStatus status);
}
