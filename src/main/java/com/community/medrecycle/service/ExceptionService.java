package com.community.medrecycle.service;

import com.community.medrecycle.config.BizException;
import com.community.medrecycle.config.NoGen;
import com.community.medrecycle.domain.*;
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
                    bag.setGrantedPoints(0);
                }
                sendBackToReview(batch, bag, "NON_EXPIRED_DRUG");
            }
            case PRESCRIPTION_INFO_EXPOSED, UNSAFE_INJECTION, LIQUID_LEAK -> {
                // 隐私泄露/针剂未安全封装/液体泄漏：须人工复核处置后才能继续流转
                if (bag.getStatus() != BagStatus.HANDED_OVER) {
                    sendBackToReview(batch, bag, record.getType().name());
                }
            }
            case WEIGHT_DISPUTE, EXCHANGE_FAILED, COMPANY_REJECT, POINTS_GRANT_FAILED -> {
                // 称重争议走 reweigh 解决；兑换失败/公司拒收/发放失败走对应专项处置流程
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

    /** 交接完成时自动记录环保公司拒收异常 */
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
        if (bag.isPointsGranted()) {
            bag.setGrantedPoints(newPoints);
        }
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
}
