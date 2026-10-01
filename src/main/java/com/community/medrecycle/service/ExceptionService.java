package com.community.medrecycle.service;

import com.community.medrecycle.config.BizException;
import com.community.medrecycle.config.NoGen;
import com.community.medrecycle.domain.*;
import com.community.medrecycle.domain.enums.BagPointsState;
import com.community.medrecycle.domain.enums.BagStatus;
import com.community.medrecycle.domain.enums.ExceptionStatus;
import com.community.medrecycle.domain.enums.ExceptionType;
import com.community.medrecycle.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 异常处理：夹带未过期药/处方信息泄露/针剂未封装/液体泄漏/称重争议/兑换失败/公司拒收 */
@Service
@RequiredArgsConstructor
public class ExceptionService {

    private final ExceptionRecordRepository exceptionRepository;
    private final RecycleBatchRepository batchRepository;
    private final DrugBagRepository bagRepository;
    private final AppointmentRepository appointmentRepository;
    private final ReviewTaskRepository reviewTaskRepository;
    private final PointsService pointsService;
    private final PointsRuleService pointsRuleService;
    private final BatchRefresher batchRefresher;

    /** 现场/居民上报异常，按类型联动袋状态与积分 */
    @Transactional
    public ExceptionRecord raise(String batchNo, String bagNo, ExceptionType type, String description) {
        RecycleBatch batch = null;
        DrugBag bag = null;
        if (bagNo != null && !bagNo.isBlank()) {
            bag = bagRepository.findByBagNo(bagNo)
                    .orElseThrow(() -> BizException.notFound("封袋不存在: " + bagNo));
            batch = batchRepository.findById(bag.getBatchId()).orElseThrow();
        } else if (batchNo != null && !batchNo.isBlank()) {
            batch = batchRepository.findByBatchNo(batchNo)
                    .orElseThrow(() -> BizException.notFound("批次不存在: " + batchNo));
        } else if (type != ExceptionType.EXCHANGE_FAILED) {
            throw BizException.badRequest("除积分兑换失败外，异常必须关联批次或封袋");
        }

        ExceptionRecord record = new ExceptionRecord();
        record.setExceptionNo(NoGen.next("EX"));
        record.setType(type);
        record.setDescription(description);
        if (batch != null) {
            record.setBatchId(batch.getId());
            record.setBatchNo(batch.getBatchNo());
            record.setResidentId(batch.getResidentId());
        }
        if (bag != null) {
            record.setBagId(bag.getId());
            record.setBagNo(bag.getBagNo());
        }
        ExceptionRecord saved = exceptionRepository.save(record);

        if (bag != null && batch != null) {
            applySideEffect(saved, batch, bag);
            batchRefresher.refresh(batch.getId());
        }
        return saved;
    }

    /** 异常副作用：让批次、袋、复核、积分联动起来 */
    private void applySideEffect(ExceptionRecord record, RecycleBatch batch, DrugBag bag) {
        switch (record.getType()) {
            case NON_EXPIRED_DRUG -> {
                // 夹带未过期药：扣回已发积分并退回人工复核
                if (bag.isPointsGranted()) {
                    pointsService.revoke(batch.getResidentId(), bag.getPoints(), "BAG", bag.getBagNo(),
                            "夹带未过期药扣回，异常单 " + record.getExceptionNo());
                    bag.setPointsGranted(false);
                    bag.setPointsState(BagPointsState.REVOKED);
                    bagRepository.save(bag);
                }
                sendBackToReview(batch, bag, "NON_EXPIRED_DRUG");
            }
            case PRESCRIPTION_INFO_EXPOSED, UNSAFE_INJECTION, LIQUID_LEAK -> {
                // 隐私泄露/针剂未安全封装/液体泄漏：须人工复核处置后才能继续流转
                if (bag.getStatus() != BagStatus.HANDED_OVER) {
                    sendBackToReview(batch, bag, record.getType().name());
                }
            }
            case WEIGHT_DISPUTE, EXCHANGE_FAILED, COMPANY_REJECT -> {
                // 称重争议走 reweigh 解决；兑换失败/公司拒收仅记录跟踪
            }
        }
    }

    private void sendBackToReview(RecycleBatch batch, DrugBag bag, String reason) {
        bag.setStatus(BagStatus.PENDING_REVIEW);
        bagRepository.save(bag);
        ReviewTask task = new ReviewTask();
        task.setTaskNo(NoGen.next("RT"));
        task.setBatchId(batch.getId());
        task.setBagId(bag.getId());
        task.setBagNo(bag.getBagNo());
        task.setReason(reason);
        reviewTaskRepository.save(task);
    }

    /** 交接完成时自动记录环保公司拒收异常：登记拒收品类、关联居民，积分已由交接流程冻结 */
    @Transactional
    public ExceptionRecord recordReject(RecycleBatch batch, DrugBag bag, String rejectReason, String companyName) {
        ExceptionRecord record = new ExceptionRecord();
        record.setExceptionNo(NoGen.next("EX"));
        record.setType(ExceptionType.COMPANY_REJECT);
        record.setDescription("环保公司" + (companyName == null ? "" : "（" + companyName + "）")
                + "拒收 " + bag.getCategory().getDescription() + " 袋 " + bag.getBagNo()
                + "，原因: " + (rejectReason == null || rejectReason.isBlank() ? "未说明" : rejectReason));
        record.setBatchId(batch.getId());
        record.setBatchNo(batch.getBatchNo());
        record.setBagId(bag.getId());
        record.setBagNo(bag.getBagNo());
        record.setResidentId(batch.getResidentId());
        record.setRejectCategory(bag.getCategory().name());
        return exceptionRepository.save(record);
    }

    /** 交接完成时自动记录异常（通用，保留兼容） */
    @Transactional
    public ExceptionRecord recordAuto(ExceptionType type, DrugBag bag, String description) {
        RecycleBatch batch = batchRepository.findById(bag.getBatchId()).orElseThrow();
        ExceptionRecord record = new ExceptionRecord();
        record.setExceptionNo(NoGen.next("EX"));
        record.setType(type);
        record.setDescription(description);
        record.setBatchId(batch.getId());
        record.setBatchNo(batch.getBatchNo());
        record.setBagId(bag.getId());
        record.setBagNo(bag.getBagNo());
        record.setResidentId(batch.getResidentId());
        return exceptionRepository.save(record);
    }

    /**
     * 社区管理员处置拒收积分：CLAWBACK 追缴（冻结积分扣回、欠费保留冲抵）
     * 或 UNFREEZE 解冻（申述成立/重新交接成功，冻结积分恢复可用、对应欠费豁免）。
     * 必须登记处理依据，并形成给居民的告知（拒收品类 + 处理依据）。
     */
    @Transactional
    public ExceptionRecord handleReject(Long adminId, String exceptionNo, String action,
                                        String regulationBasis, String residentNotice) {
        ExceptionRecord record = get(exceptionNo);
        if (record.getType() != ExceptionType.COMPANY_REJECT) {
            throw BizException.badRequest("仅环保公司拒收异常支持冻结/追缴处置");
        }
        if (record.getStatus() == ExceptionStatus.RESOLVED) {
            throw BizException.conflict("拒收异常已处置: " + record.getResolutionAction());
        }
        if (regulationBasis == null || regulationBasis.isBlank()) {
            throw BizException.badRequest("必须填写处理依据（制度条款/拒收名录条目）");
        }
        boolean clawback = "CLAWBACK".equalsIgnoreCase(action);
        boolean unfreeze = "UNFREEZE".equalsIgnoreCase(action);
        if (!clawback && !unfreeze) {
            throw BizException.badRequest("处置动作只支持 CLAWBACK（追缴）或 UNFREEZE（解冻）");
        }

        DrugBag bag = bagRepository.findById(record.getBagId())
                .orElseThrow(() -> BizException.notFound("关联封袋不存在"));
        RecycleBatch batch = batchRepository.findById(bag.getBatchId()).orElseThrow();
        int frozenPart = bag.getFrozenPoints();
        int debtPart = Math.max(0, bag.getPoints() - frozenPart);

        String conclusion;
        if (clawback) {
            if (bag.getPointsState() != BagPointsState.FROZEN) {
                throw BizException.conflict("该封袋积分不处于拒收冻结状态，当前状态: " + bag.getPointsState());
            }
            if (frozenPart > 0) {
                pointsService.clawback(batch.getResidentId(), frozenPart, "BAG", bag.getBagNo(),
                        "拒收追缴，异常单 " + record.getExceptionNo() + "，依据: " + regulationBasis);
            }
            bag.setPointsGranted(false);
            bag.setPointsState(BagPointsState.REVOKED);
            bag.setFrozenPoints(0);
            bagRepository.save(bag);
            conclusion = frozenPart > 0
                    ? "已追缴冻结积分 " + frozenPart + " 分"
                            + (debtPart > 0 ? "，另有 " + debtPart + " 分因已兑换转为欠费，后续积分自动冲抵" : "")
                    : "积分在拒收前已被兑换，" + debtPart + " 分已登记欠费，后续积分自动冲抵";
        } else {
            if (bag.getPointsState() != BagPointsState.FROZEN || frozenPart <= 0) {
                throw BizException.conflict("该封袋没有可解冻的冻结积分，当前状态: " + bag.getPointsState());
            }
            if (frozenPart > 0) {
                pointsService.unfreezeRejected(batch.getResidentId(), frozenPart, debtPart,
                        "BAG", bag.getBagNo(),
                        "拒收申述成立/重新交接成功，积分解除冻结，异常单 " + record.getExceptionNo());
            }
            bag.setPointsState(BagPointsState.GRANTED);
            bag.setFrozenPoints(0);
            bagRepository.save(bag);
            conclusion = "冻结积分 " + frozenPart + " 分已解除冻结恢复可用"
                    + (debtPart > 0 ? "，已兑换部分 " + debtPart + " 分欠费一并豁免" : "");
        }

        record.setResolutionAction(clawback ? "CLAWBACK" : "UNFREEZE");
        record.setRegulationBasis(regulationBasis);
        record.setAffectedPoints(bag.getPoints());
        String notice = (residentNotice == null || residentNotice.isBlank())
                ? String.format("您于批次 %s 交投的%s（袋号 %s，%d 积分）被环保公司拒收。%s。处理依据：%s。",
                        batch.getBatchNo(), bag.getCategory().getDescription(), bag.getBagNo(),
                        bag.getPoints(), conclusion, regulationBasis)
                : residentNotice;
        record.setResidentNotice(notice);
        record.setStatus(ExceptionStatus.RESOLVED);
        record.setHandlerId(adminId);
        record.setResolution(conclusion);
        record.setResolvedAt(LocalDateTime.now());
        return exceptionRepository.save(record);
    }

    @Transactional
    public ExceptionRecord process(Long handlerId, String exceptionNo) {
        ExceptionRecord record = get(exceptionNo);
        if (record.getStatus() != ExceptionStatus.OPEN) {
            throw BizException.conflict("异常当前状态不可受理: " + record.getStatus());
        }
        record.setStatus(ExceptionStatus.PROCESSING);
        record.setHandlerId(handlerId);
        return exceptionRepository.save(record);
    }

    @Transactional
    public ExceptionRecord resolve(Long handlerId, String exceptionNo, String resolution) {
        ExceptionRecord record = get(exceptionNo);
        if (record.getStatus() == ExceptionStatus.RESOLVED) {
            throw BizException.conflict("异常已办结");
        }
        record.setStatus(ExceptionStatus.RESOLVED);
        record.setHandlerId(handlerId);
        record.setResolution(resolution);
        record.setResolvedAt(LocalDateTime.now());
        return exceptionRepository.save(record);
    }

    /** 称重争议复秤：调整袋重并按预约快照重算积分（多退少补），随后办结异常 */
    @Transactional
    public ExceptionRecord reweigh(Long handlerId, String exceptionNo, double newWeightKg) {
        if (newWeightKg <= 0) {
            throw BizException.badRequest("复秤重量必须大于 0");
        }
        ExceptionRecord record = get(exceptionNo);
        if (record.getType() != ExceptionType.WEIGHT_DISPUTE) {
            throw BizException.badRequest("仅称重争议异常支持复秤");
        }
        if (record.getStatus() == ExceptionStatus.RESOLVED) {
            throw BizException.conflict("异常已办结");
        }
        DrugBag bag = bagRepository.findById(record.getBagId())
                .orElseThrow(() -> BizException.notFound("关联封袋不存在"));
        if (bag.getStatus() == BagStatus.HANDED_OVER) {
            throw BizException.conflict("封袋已交接，不可复秤");
        }
        RecycleBatch batch = batchRepository.findById(bag.getBatchId()).orElseThrow();
        Appointment appointment = appointmentRepository.findById(batch.getAppointmentId()).orElseThrow();

        double oldWeight = bag.getWeightKg();
        int oldPoints = bag.getPoints();
        int newPoints = pointsRuleService.pointsFor(
                appointment.getPointsRuleSnapshot(), bag.getCategory(), newWeightKg, batch.getPointsMultiplier());

        bag.setWeightKg(newWeightKg);
        bag.setPoints(newPoints);
        bagRepository.save(bag);

        if (bag.isPointsGranted() && newPoints != oldPoints) {
            pointsService.adjust(batch.getResidentId(), newPoints - oldPoints, "BAG", bag.getBagNo(),
                    String.format("称重争议复秤 %.2fkg -> %.2fkg，积分 %d -> %d",
                            oldWeight, newWeightKg, oldPoints, newPoints));
        }

        batchRefresher.refresh(batch.getId());
        return resolve(handlerId, exceptionNo,
                String.format("复秤 %.2fkg -> %.2fkg，积分 %d -> %d", oldWeight, newWeightKg, oldPoints, newPoints));
    }

    @Transactional(readOnly = true)
    public ExceptionRecord get(String exceptionNo) {
        return exceptionRepository.findByExceptionNo(exceptionNo)
                .orElseThrow(() -> BizException.notFound("异常不存在: " + exceptionNo));
    }

    @Transactional(readOnly = true)
    public List<ExceptionRecord> list(ExceptionStatus status) {
        return status == null
                ? exceptionRepository.findAll()
                : exceptionRepository.findByStatusOrderByCreatedAtDesc(status);
    }

    /** 居民查看与本人相关的异常告知（拒收品类、处理依据、处置结论） */
    @Transactional(readOnly = true)
    public List<ExceptionRecord> listByResident(Long residentId) {
        return exceptionRepository.findByResidentIdOrderByCreatedAtDesc(residentId);
    }
}
