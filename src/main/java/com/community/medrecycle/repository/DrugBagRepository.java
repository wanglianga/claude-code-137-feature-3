package com.community.medrecycle.repository;

import com.community.medrecycle.domain.DrugBag;
import com.community.medrecycle.domain.enums.BagStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DrugBagRepository extends JpaRepository<DrugBag, Long> {
    Optional<DrugBag> findByBagNo(String bagNo);
    Optional<DrugBag> findBySealNo(String sealNo);
    List<DrugBag> findByBatchId(Long batchId);
    List<DrugBag> findByBatchIdIn(List<Long> batchIds);
    List<DrugBag> findBySiteIdAndStatus(Long siteId, BagStatus status);
}
