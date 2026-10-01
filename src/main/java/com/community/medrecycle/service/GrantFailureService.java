package com.community.medrecycle.service;

import com.community.medrecycle.config.BizException;
import com.community.medrecycle.config.NoGen;
import com.community.medrecycle.domain.*;
import com.community.medrecycle.domain.enums.*;
import com.community.medrecycle.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 积分发放失败的客服复核：补发 / 改纸质兑换券 / 驳回。
 * 失败单状态机 + 袋发放标志双重幂等，杜绝重复补发；
 * 补发后向客服展示该积分是否已被兑换、账户当前状态。
 */
@Service
@RequiredArgsConstructor
public class GrantFailureService {

    private final PointsGrantFailureRepository failureRepository;
    private final DrugBagRepository bagRepository;
    private final RecycleBatchRepository batchRepository;
    private final PointsAccountRepository accountRepository;
    private final PointsTransactionRepository transactionRepository;
    private final PointsExchangeRepository exchangeRepository;
    private final PointsService pointsService;

    @Transactional(readOnly = true)
    public List<PointsGrantFailure> list(GrantFailureStatus status, Long residentId) {
        if (residentId != null) {
            return failureRepository.findByResidentIdOrderByCreatedAtDesc(residentId);
        }
        return status == null
                ? failureRepository.findAll()
                : failureRepository.findByStatusOrderByCreatedAtDesc(status);
    }

    @Transactional(readOnly = true)
    public PointsGrantFailure get(String failureNo) {
        return failureRepository.findByFailureNo(failureNo)
                .orElseThrow(() -> BizException.notFound("积分发放失败单不存在: " + failureNo));
    }

    /** 客服复核视图：失败单全貌 + 账户状态 + 补发积分是否已被兑换 */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(String failureNo) {
        PointsGrantFailure failure = get(failureNo);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("failure", failure);
        batchRepository.findById(failure.getBatchId()).ifPresent(b -> view.put("batch", b));
        bagRepository.findById(failure.getBagId()).ifPresent(b -> view.put("bag", b));

        PointsAccount account = accountRepository.findByResidentId(failure.getResidentId()).orElse(null);
        Map<String, Object> accountView = new LinkedHashMap<>();
        if (account == null) {
            accountView.put("exists", false);
            accountView.put("status", AccountStatus.NOT_FOUND);
        } else {
            accountView.put("exists", true);
            accountView.put("status", account.getStatus());
            accountView.put("balance", account.getBalance());
            accountView.put("frozenPoints", account.getFrozenPoints());
            accountView.put("availablePoints", account.availablePoints());
            accountView.put("debtPoints", account.getDebtPoints());
        }
        view.put("account", accountView);

        // 防重复补发：补发后居民是否已发生成功兑换，以及补发流水当前可查状态
        boolean reissued = failure.getStatus() == GrantFailureStatus.REISSUED;
        view.put("reissued", reissued);
        if (reissued && failure.getReissueTxnId() != null) {
            PointsTransaction txn = transactionRepository.findById(failure.getReissueTxnId()).orElse(null);
            view.put("reissueTxn", txn);
            view.put("exchangedAfterReissue", exchangeRepository.existsByResidentIdAndStatusAndCreatedAtAfter(
                    failure.getResidentId(), ExchangeStatus.SUCCESS, failure.getHandledAt()));
            view.put("successfulExchanges", exchangeRepository
                    .findByResidentIdOrderByCreatedAtDesc(failure.getResidentId()).stream()
                    .filter(e -> e.getStatus() == ExchangeStatus.SUCCESS
                            && failure.getHandledAt() != null
                            && !e.getCreatedAt().isBefore(failure.getHandledAt()))
                    .toList());
        } else {
            view.put("exchangedAfterReissue", false);
            view.put("successfulExchanges", List.of());
        }
        return view;
    }

    /** 补发积分到居民账户 */
    @Transactional
    public PointsGrantFailure reissue(Long serviceId, String failureNo, String note) {
        PointsGrantFailure failure = get(failureNo);
        requirePending(failure);
        DrugBag bag = bagRepository.findById(failure.getBagId())
                .orElseThrow(() -> BizException.notFound("关联封袋不存在"));
        if (bag.isPointsGranted()) {
            throw BizException.conflict("该封袋积分实际已到账，不能重复补发");
        }
        if (bag.getStatus() == BagStatus.REVIEW_REJECTED) {
            throw BizException.conflict("该封袋已被人工复核拒绝，不能补发");
        }

        PointsTransaction txn = pointsService.reissue(failure.getResidentId(), failure.getPoints(),
                failure.getFailureNo(), "客服复核补发，失败单 " + failure.getFailureNo()
                        + (note == null || note.isBlank() ? "" : "；备注：" + note));

        failure.setStatus(GrantFailureStatus.REISSUED);
        failure.setHandlerId(serviceId);
        failure.setReviewNote(note);
        failure.setReissueTxnId(txn.getId());
        failure.setHandledAt(LocalDateTime.now());
        failureRepository.save(failure);

        bag.setPointsGranted(true);
        bag.setPointsState(BagPointsState.GRANTED);
        bagRepository.save(bag);
        return failure;
    }

    /** 改为纸质兑换券：生成券号，不往积分账户入账 */
    @Transactional
    public PointsGrantFailure paperVoucher(Long serviceId, String failureNo, String voucherNo, String note) {
        PointsGrantFailure failure = get(failureNo);
        requirePending(failure);
        DrugBag bag = bagRepository.findById(failure.getBagId())
                .orElseThrow(() -> BizException.notFound("关联封袋不存在"));
        if (bag.isPointsGranted()) {
            throw BizException.conflict("该封袋积分已到账，不能改发纸质兑换券");
        }

        String no = (voucherNo == null || voucherNo.isBlank()) ? NoGen.next("PV") : voucherNo;
        failure.setStatus(GrantFailureStatus.PAPER_VOUCHER);
        failure.setHandlerId(serviceId);
        failure.setVoucherNo(no);
        failure.setReviewNote(note);
        failure.setHandledAt(LocalDateTime.now());
        failureRepository.save(failure);

        // 纸券替代电子积分：本袋积分不再进入电子账户
        bag.setPointsGranted(false);
        bag.setPointsState(BagPointsState.PAPER_VOUCHER);
        bagRepository.save(bag);
        return failure;
    }

    /** 驳回异常积分：不予发放 */
    @Transactional
    public PointsGrantFailure reject(Long serviceId, String failureNo, String note) {
        PointsGrantFailure failure = get(failureNo);
        requirePending(failure);
        if (note == null || note.isBlank()) {
            throw BizException.badRequest("驳回必须填写复核说明");
        }
        DrugBag bag = bagRepository.findById(failure.getBagId())
                .orElseThrow(() -> BizException.notFound("关联封袋不存在"));

        failure.setStatus(GrantFailureStatus.REJECTED);
        failure.setHandlerId(serviceId);
        failure.setReviewNote(note);
        failure.setHandledAt(LocalDateTime.now());
        failureRepository.save(failure);

        bag.setPointsGranted(false);
        bag.setPointsState(BagPointsState.REVOKED);
        bagRepository.save(bag);
        return failure;
    }

    private void requirePending(PointsGrantFailure failure) {
        if (failure.getStatus() != GrantFailureStatus.PENDING) {
            throw BizException.conflict("失败单已处理，当前结论: " + failure.getStatus());
        }
    }
}
