package com.community.medrecycle.repository;

import com.community.medrecycle.domain.PointsIssue;
import com.community.medrecycle.domain.enums.IssueStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PointsIssueRepository extends JpaRepository<PointsIssue, Long> {
    Optional<PointsIssue> findByIssueNo(String issueNo);

    /** 客服处置加悲观锁，防止并发重复补发 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from PointsIssue i where i.issueNo = :issueNo")
    Optional<PointsIssue> lockByIssueNo(@Param("issueNo") String issueNo);

    /** 每个封袋至多一条失败单，用于发放失败幂等落单 */
    Optional<PointsIssue> findByBagNo(String bagNo);

    List<PointsIssue> findByStatusOrderByCreatedAtDesc(IssueStatus status);

    List<PointsIssue> findByResidentIdOrderByCreatedAtDesc(Long residentId);

    List<PointsIssue> findByResidentIdAndStatusOrderByCreatedAtDesc(Long residentId, IssueStatus status);

    long countByStatus(IssueStatus status);
}
