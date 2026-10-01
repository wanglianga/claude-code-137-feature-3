package com.community.medrecycle.service;

import com.community.medrecycle.config.BizException;
import com.community.medrecycle.domain.*;
import com.community.medrecycle.domain.enums.BagStatus;
import com.community.medrecycle.domain.enums.ExceptionStatus;
import com.community.medrecycle.domain.enums.GrantFailureStatus;
import com.community.medrecycle.domain.enums.ReviewStatus;
import com.community.medrecycle.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 社区管理员看板：超期、双人交接、上门量、容量预警一屏掌握 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final CommunitySiteRepository siteRepository;
    private final StorageCabinetRepository cabinetRepository;
    private final StorageEntryRepository entryRepository;
    private final DrugBagRepository bagRepository;
    private final ReviewTaskRepository reviewTaskRepository;
    private final ExceptionRecordRepository exceptionRepository;
    private final PointsGrantFailureRepository grantFailureRepository;
    private final StorageService storageService;

    @Transactional(readOnly = true)
    public Map<String, Object> dashboard(Long siteId) {
        CommunitySite site = siteRepository.findById(siteId)
                .orElseThrow(() -> BizException.notFound("社区回收点不存在: " + siteId));

        // 暂存超期
        LocalDateTime cutoff = LocalDateTime.now().minusDays(site.getOverdueDays());
        List<StorageEntry> overdueEntries = entryRepository
                .findBySiteIdAndOutAtIsNullAndInAtBefore(siteId, cutoff);

        // 待交接（暂存中）统计
        List<DrugBag> storedBags = bagRepository.findBySiteIdAndStatus(siteId, BagStatus.STORED);
        double readyWeight = storedBags.stream().mapToDouble(DrugBag::getWeightKg).sum();
        boolean anyHighRisk = storedBags.stream().anyMatch(DrugBag::isHighRisk);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("site", site);
        result.put("cabinets", storageService.cabinets(siteId));
        result.put("capacityAlerts", storageService.alerts(siteId));
        result.put("overdue", Map.of(
                "overdueDaysLimit", site.getOverdueDays(),
                "count", overdueEntries.size(),
                "entries", overdueEntries.stream().map(e -> {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("bagNo", e.getBagNo());
                    m.put("inAt", e.getInAt());
                    m.put("storedDays", ChronoUnit.DAYS.between(e.getInAt(), LocalDateTime.now()));
                    return m;
                }).toList()));
        result.put("pickup", Map.of(
                "storedBags", storedBags.size(),
                "storedWeightKg", readyWeight,
                "pickupThresholdKg", site.getPickupThresholdKg(),
                "thresholdReached", readyWeight >= site.getPickupThresholdKg()));
        result.put("handover", Map.of(
                "requiresDualIfHandoverNow", anyHighRisk || readyWeight >= site.getDualWeightThresholdKg(),
                "dualWeightThresholdKg", site.getDualWeightThresholdKg(),
                "containsHighRisk", anyHighRisk));
        result.put("pendingReviews", reviewTaskRepository.findByStatusOrderByCreatedAtAsc(ReviewStatus.PENDING).size());
        result.put("openExceptions", exceptionRepository.countByStatus(ExceptionStatus.OPEN)
                + exceptionRepository.countByStatus(ExceptionStatus.PROCESSING));
        result.put("pendingGrantFailures", grantFailureRepository.countByStatus(GrantFailureStatus.PENDING));
        return result;
    }
}
