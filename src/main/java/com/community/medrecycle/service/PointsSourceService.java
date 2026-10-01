package com.community.medrecycle.service;

import com.community.medrecycle.domain.*;
import com.community.medrecycle.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 居民积分来源视图：每一袋药品的品类、重量、适用积分规则、积分与处理状态
 * （正常/冻结/已追缴/纸质券/发放失败待补发），拒收时附带告知与处理依据。
 */
@Service
@RequiredArgsConstructor
public class PointsSourceService {

    private final RecycleBatchRepository batchRepository;
    private final DrugBagRepository bagRepository;
    private final AppointmentRepository appointmentRepository;
    private final PointsGrantFailureRepository failureRepository;
    private final ExceptionRecordRepository exceptionRepository;
    private final PointsRuleService pointsRuleService;
    private final PointsAccountRepository accountRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> sourcesOf(Long residentId) {
        List<RecycleBatch> batches = batchRepository.findByResidentIdOrderByCreatedAtDesc(residentId);
        List<Long> batchIds = batches.stream().map(RecycleBatch::getId).toList();
        List<DrugBag> bags = batchIds.isEmpty() ? List.of() : bagRepository.findByBatchIdIn(batchIds);

        Map<Long, RecycleBatch> batchMap = batches.stream()
                .collect(Collectors.toMap(RecycleBatch::getId, b -> b));
        Map<String, PointsGrantFailure> failureMap = failureRepository
                .findByResidentIdOrderByCreatedAtDesc(residentId).stream()
                .collect(Collectors.toMap(PointsGrantFailure::getBagNo, f -> f, (a, b) -> a));
        Map<String, ExceptionRecord> rejectMap = exceptionRepository
                .findByResidentIdOrderByCreatedAtDesc(residentId).stream()
                .filter(e -> e.getBagNo() != null)
                .collect(Collectors.toMap(ExceptionRecord::getBagNo, e -> e, (a, b) -> a));

        List<Map<String, Object>> sources = new ArrayList<>();
        for (DrugBag bag : bags) {
            RecycleBatch batch = batchMap.get(bag.getBatchId());
            Appointment appointment = appointmentRepository.findById(batch.getAppointmentId()).orElseThrow();

            Map<String, Object> view = new LinkedHashMap<>();
            view.put("batchNo", batch.getBatchNo());
            view.put("bagNo", bag.getBagNo());
            view.put("category", bag.getCategory());
            view.put("categoryDesc", bag.getCategory().getDescription());
            view.put("weightKg", bag.getWeightKg());
            view.put("pointsPerKg", pointsRuleService.rateFor(appointment.getPointsRuleSnapshot(), bag.getCategory()));
            view.put("pointsMultiplier", batch.getPointsMultiplier());
            view.put("points", bag.getPoints());
            view.put("pointsState", bag.getPointsState());
            view.put("stateDesc", stateDesc(bag));
            view.put("frozenPoints", bag.getFrozenPoints());

            PointsGrantFailure failure = failureMap.get(bag.getBagNo());
            if (failure != null) {
                Map<String, Object> fv = new LinkedHashMap<>();
                fv.put("failureNo", failure.getFailureNo());
                fv.put("status", failure.getStatus());
                fv.put("failReason", failure.getFailReason());
                fv.put("voucherNo", failure.getVoucherNo());
                fv.put("reviewNote", failure.getReviewNote());
                fv.put("handledAt", failure.getHandledAt());
                view.put("grantFailure", fv);
            }

            ExceptionRecord reject = rejectMap.get(bag.getBagNo());
            if (reject != null) {
                Map<String, Object> rv = new LinkedHashMap<>();
                rv.put("exceptionNo", reject.getExceptionNo());
                rv.put("rejectCategory", reject.getRejectCategory());
                rv.put("description", reject.getDescription());
                rv.put("resolutionAction", reject.getResolutionAction());
                rv.put("regulationBasis", reject.getRegulationBasis());
                rv.put("residentNotice", reject.getResidentNotice());
                rv.put("status", reject.getStatus());
                view.put("rejectInfo", rv);
            }
            sources.add(view);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        PointsAccount account = accountRepository.findByResidentId(residentId).orElse(null);
        if (account != null) {
            Map<String, Object> av = new LinkedHashMap<>();
            av.put("status", account.getStatus());
            av.put("balance", account.getBalance());
            av.put("frozenPoints", account.getFrozenPoints());
            av.put("availablePoints", account.availablePoints());
            av.put("debtPoints", account.getDebtPoints());
            result.put("account", av);
        }
        result.put("sources", sources);
        return result;
    }

    private String stateDesc(DrugBag bag) {
        return switch (bag.getPointsState()) {
            case NOT_GRANTED -> "发放失败，待客服复核补发";
            case GRANTED -> "已发放，正常";
            case FROZEN -> "批次被拒收，积分冻结待追缴/解冻";
            case REVOKED -> "已追缴/驳回";
            case PAPER_VOUCHER -> "已改为纸质兑换券";
        };
    }
}
