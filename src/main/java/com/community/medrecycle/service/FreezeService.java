package com.community.medrecycle.service;

import com.community.medrecycle.config.BizException;
import com.community.medrecycle.config.NoGen;
import com.community.medrecycle.domain.*;
import com.community.medrecycle.domain.enums.FreezeSource;
import com.community.medrecycle.domain.enums.FreezeStatus;
import com.community.medrecycle.domain.enums.IssueStatus;
import com.community.medrecycle.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 拒收冻结/追缴：环保公司拒收某袋后，该袋已发积分进入冻结流程，
 * 可用余额足够当场冻结，不足部分形成追缴欠分（居民后续入账自动抵扣，也可管理员主动追缴）；
 * 社区管理员登记拒收品类与处理依据，向居民说明；申诉成立可解除冻结退回积分。
 */
@Service
@RequiredArgsConstructor
public class FreezeService {

    private final PointsFreezeRepository freezeRepository;
    private final PointsIssueRepository issueRepository;
    private final DrugBagRepository bagRepository;
    private final RecycleBatchRepository batchRepository;
    private final PointsService pointsService;

    /** 环保公司拒收联动：对该袋实际已发积分发起冻结（幂等：每袋一单）；无已发积分则跳过 */
    @Transactional
    public PointsFreeze freezeForRejectedBag(DrugBag bag, String rejectReason, String exceptionNo) {
        int granted = bag.getGrantedPoints();
        if (granted <= 0) {
            return null;
        }
        RecycleBatch batch = batchRepository.findById(bag.getBatchId()).orElseThrow();
        PointsFreeze freeze = freezeRepository.findByBagNo(bag.getBagNo()).orElseGet(() -> {
            PointsFreeze f = new PointsFreeze();
            f.setFreezeNo(NoGen.next("FZ"));
            f.setResidentId(batch.getResidentId());
            f.setBatchId(batch.getId());
            f.setBagId(bag.getId());
            f.setBagNo(bag.getBagNo());
            f.setCategory(bag.getCategory());
            return f;
        });
        freeze.setResidentId(batch.getResidentId());
        freeze.setBatchNo(batch.getBatchNo());
        freeze.setTotalPoints(granted);
        freeze.setRejectedCategory(bag.getCategory().name());
        freeze.setRejectReason(rejectReason);
        freeze.setExceptionNo(exceptionNo);
        // 判定积分来源：客服补发 vs 原始发放
        freeze.setSource(issueRepository.findByBagNo(bag.getBagNo())
                .filter(i -> i.getStatus() == IssueStatus.REISSUED).isPresent()
                ? FreezeSource.REISSUE : FreezeSource.INITIAL_GRANT);

        PointsService.FreezeMovement m = pointsService.applyFreezeMovement(
                batch.getResidentId(), granted, freeze.getFreezeNo(),
                "环保公司拒收冻结 " + bag.getCategory() + " 袋 " + bag.getBagNo());
        freeze.setFrozenPoints(m.frozenNow());
        freeze.setOutstandingPoints(m.outstanding());
        freeze.setRecoveredPoints(granted - m.frozenNow() - m.outstanding());
        freeze.setStatus(FreezeStatus.FROZEN);
        PointsFreeze saved = freezeRepository.save(freeze);

        // 关联仍在处理中的发放失败单，供客服参考
        issueRepository.findByBagNo(bag.getBagNo()).ifPresent(issue -> {
            if (issue.getStatus() == IssueStatus.PENDING) {
                issue.setRelatedFreezeId(saved.getId());
                issueRepository.save(issue);
            }
        });
        return saved;
    }

    /** 社区管理员登记拒收品类与处理依据，用于向居民说明 */
    @Transactional
    public PointsFreeze recordBasis(Long adminId, String freezeNo, String rejectedCategory, String handlingBasis) {
        PointsFreeze freeze = lock(freezeNo);
        if (rejectedCategory != null && !rejectedCategory.isBlank()) {
            freeze.setRejectedCategory(rejectedCategory.trim());
        }
        freeze.setHandlingBasis(handlingBasis);
        freeze.setBasisRecordedBy(adminId);
        freeze.setBasisRecordedAt(LocalDateTime.now());
        return freezeRepository.save(freeze);
    }

    /**
     * 主动追缴：用居民当前可用余额偿还欠分；欠分偿清后冻结积分一并核销，
     * 冻结单进入 RECOVERED。居民后续入账时也会自动抵扣，此接口供管理员即时追缴。
     */
    @Transactional
    public PointsFreeze recover(Long adminId, String freezeNo, String note) {
        PointsFreeze freeze = lock(freezeNo);
        if (freeze.getStatus() != FreezeStatus.FROZEN) {
            throw BizException.conflict("冻结单当前状态不可追缴: " + freeze.getStatus());
        }
        int balance = pointsService.balanceOf(freeze.getResidentId());
        int take = Math.min(balance, freeze.getOutstandingPoints());
        if (take > 0) {
            // 直接从可用余额追缴，积分离开居民账户
            pointsService.adjust(freeze.getResidentId(), -take, "FREEZE", freeze.getFreezeNo(),
                    "拒收追缴可用积分 " + take);
            freeze.setOutstandingPoints(freeze.getOutstandingPoints() - take);
            freeze.setRecoveredPoints(freeze.getRecoveredPoints() + take);
        }

        if (freeze.getOutstandingPoints() == 0) {
            // 欠分偿清：尚在冻结余额中的积分予以核销（不退回居民）
            int forfeited = freeze.getFrozenPoints();
            if (forfeited > 0) {
                pointsService.forfeitFrozen(freeze.getResidentId(), forfeited, freeze.getFreezeNo(),
                        "拒收冻结积分追缴核销");
                freeze.setFrozenPoints(0);
                freeze.setRecoveredPoints(freeze.getRecoveredPoints() + forfeited);
            }
            freeze.setStatus(FreezeStatus.RECOVERED);
            freeze.setHandledBy(adminId);
            freeze.setHandleNote(note);
            freeze.setHandledAt(LocalDateTime.now());
        } else {
            freeze.setHandledBy(adminId);
            freeze.setHandleNote("部分追缴 " + take + "，尚欠 " + freeze.getOutstandingPoints()
                    + (note == null ? "" : "；" + note));
            freeze.setHandledAt(LocalDateTime.now());
        }
        return freezeRepository.save(freeze);
    }

    /** 申诉成立/拒收复核撤销：解除冻结，把冻结积分退回居民可用余额（已追缴部分不退） */
    @Transactional
    public PointsFreeze release(Long adminId, String freezeNo, String note) {
        PointsFreeze freeze = lock(freezeNo);
        if (freeze.getStatus() != FreezeStatus.FROZEN) {
            throw BizException.conflict("冻结单当前状态不可解冻: " + freeze.getStatus());
        }
        if (freeze.getOutstandingPoints() > 0) {
            throw BizException.conflict("尚有 " + freeze.getOutstandingPoints() + " 追缴欠分，需先处理完毕再解冻");
        }
        int back = freeze.getFrozenPoints();
        if (back > 0) {
            pointsService.unfreezeMovement(freeze.getResidentId(), back, freeze.getFreezeNo(),
                    "拒收申诉成立解除冻结退回积分");
            freeze.setFrozenPoints(0);
        }
        freeze.setStatus(FreezeStatus.RELEASED);
        freeze.setHandledBy(adminId);
        freeze.setHandleNote(note);
        freeze.setHandledAt(LocalDateTime.now());
        return freezeRepository.save(freeze);
    }

    private PointsFreeze lock(String freezeNo) {
        return freezeRepository.lockByFreezeNo(freezeNo)
                .orElseThrow(() -> BizException.notFound("冻结单不存在: " + freezeNo));
    }

    @Transactional(readOnly = true)
    public PointsFreeze get(String freezeNo) {
        return freezeRepository.findByFreezeNo(freezeNo)
                .orElseThrow(() -> BizException.notFound("冻结单不存在: " + freezeNo));
    }

    @Transactional(readOnly = true)
    public List<PointsFreeze> list(FreezeStatus status) {
        return status == null
                ? freezeRepository.findAll(org.springframework.data.domain.Sort.by("createdAt").descending())
                : freezeRepository.findByStatusOrderByCreatedAtDesc(status);
    }

    @Transactional(readOnly = true)
    public List<PointsFreeze> listForResident(Long residentId) {
        return freezeRepository.findByResidentIdOrderByCreatedAtDesc(residentId);
    }

    /** 居民/管理员查看冻结追缴详情与处理依据 */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(String freezeNo) {
        PointsFreeze freeze = get(freezeNo);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("freeze", freeze);
        view.put("residentId", freeze.getResidentId());
        view.put("currentAvailable", pointsService.balanceOf(freeze.getResidentId()));
        view.put("basisRecorded", freeze.getHandlingBasis() != null);
        view.put("fullyResolved", freeze.getStatus() != FreezeStatus.FROZEN);
        return view;
    }
}
