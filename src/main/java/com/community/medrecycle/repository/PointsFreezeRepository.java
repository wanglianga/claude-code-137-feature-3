package com.community.medrecycle.repository;

import com.community.medrecycle.domain.PointsFreeze;
import com.community.medrecycle.domain.enums.FreezeStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PointsFreezeRepository extends JpaRepository<PointsFreeze, Long> {
    Optional<PointsFreeze> findByFreezeNo(String freezeNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from PointsFreeze f where f.freezeNo = :freezeNo")
    Optional<PointsFreeze> lockByFreezeNo(@Param("freezeNo") String freezeNo);

    /** 每个封袋至多一条拒收冻结单，拒收冻结幂等 */
    Optional<PointsFreeze> findByBagNo(String bagNo);

    List<PointsFreeze> findByStatusOrderByCreatedAtDesc(FreezeStatus status);

    List<PointsFreeze> findByResidentIdOrderByCreatedAtDesc(Long residentId);

    /** 居民尚有欠分、等待后续入账抵扣的冻结单（最旧优先） */
    List<PointsFreeze> findByResidentIdAndStatusAndOutstandingPointsGreaterThanOrderByCreatedAtAsc(
            Long residentId, FreezeStatus status, int gt);
}
