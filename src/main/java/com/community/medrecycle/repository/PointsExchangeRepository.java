package com.community.medrecycle.repository;

import com.community.medrecycle.domain.PointsExchange;
import com.community.medrecycle.domain.enums.ExchangeStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface PointsExchangeRepository extends JpaRepository<PointsExchange, Long> {
    List<PointsExchange> findByResidentIdOrderByCreatedAtDesc(Long residentId);

    /** 补发后居民是否已发生成功兑换（客服据此防止重复补发） */
    boolean existsByResidentIdAndStatusAndCreatedAtAfter(Long residentId, ExchangeStatus status,
                                                         LocalDateTime after);
}
