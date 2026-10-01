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
import java.util.*;

/**
 * 积分发放失败补发：称重后积分发放失败时落失败单（含失败原因/积分规则/药品批次/账户状态快照），
 * 客服复核后可补发积分、改为纸质兑换券或驳回异常积分；处置全程幂等防重发，
 * 并回显居民是否已发生兑换。补发完成后居民可查看积分来源与处理状态。
 */
@Service
@RequiredArgsConstructor
public class PointsIssueService {

    private final PointsIssueRepository issueRepository;
    private final PaperVoucherRepository voucherRepository;
    private final PointsFreezeRepository freezeRepository;
    private final DrugBagRepository bagRepository;
    private final RecycleBatchRepository batchRepository;
    private final ExceptionRecordRepository exceptionRepository;
    private final PointsExchangeRepository exchangeRepository;
    private final PointsService pointsService;
    private final BatchRefresher batchRefresher;

    /** 称重/复核发放积分失败时落单（幂等：同一封袋只保留一条失败单），并自动登记异常 */
    @Transactional
    public PointsIssue recordGrantFailure(RecycleBatch batch, DrugBag bag, Appointment appointment,
                                          GrantFailReason reason, String detail) {
        PointsIssue issue = issueRepository.findByBagNo(bag.getBagNo()).orElseGet(() -> {
            PointsIssue i = new PointsIssue();
            i.setIssueNo(NoGen.next("PI"));
            i.setResidentId(batch.getResidentId());
            i.setBatchId(batch.getId());
            i.setBatchNo(batch.getBatchNo());
            i.setBagId(bag.getId());
            i.setBagNo(bag.getBagNo());
            i.setCategory(bag.getCategory());
            return i; // 先构造，字段填齐后统一保存
        });
        if (issue.getStatus() != IssueStatus.PENDING) {
            return issue; // 已处置，不覆盖
        }

        issue.setExpectedPoints(bag.getPoints());
        issue.setPointsRuleSnapshot(appointment.getPointsRuleSnapshot());
        issue.setPointsMultiplier(batch.getPointsMultiplier());
        issue.setWeightKg(bag.getWeightKg());
        issue.setFailReason(reason);
        issue.setFailDetail(truncate(detail, 500));

        PointsService.AccountSnapshot snap = pointsService.snapshot(batch.getResidentId());
        issue.setAccountStatusAtFailure(snap.status() == null ? AccountStatus.ACTIVE : snap.status());
        issue.setBalanceAtFailure(snap.balance());

        PointsIssue saved = issueRepository.save(issue);

        // 确保袋未标记已发、实际已发为 0
        bag.setPointsGranted(false);
        bag.setGrantedPoints(0);
        bagRepository.save(bag);

        // 自动登记"积分发放失败"异常，便于异常看板跟踪
        if (issue.getExceptionNo() == null) {
            ExceptionRecord ex = new ExceptionRecord();
            ex.setExceptionNo(NoGen.next("EX"));
            ex.setBatchId(batch.getId());
            ex.setBatchNo(batch.getBatchNo());
            ex.setBagId(bag.getId());
            ex.setBagNo(bag.getBagNo());
            ex.setType(ExceptionType.POINTS_GRANT_FAILED);
            ex.setDescription("居民称重后积分发放失败：品类 " + bag.getCategory()
                    + "，应发 " + bag.getPoints() + " 分，原因 " + reason
                    + (detail == null ? "" : "（" + detail + "）"));
            exceptionRepository.save(ex);
            saved.setExceptionNo(ex.getExceptionNo());
            saved = issueRepository.save(saved);
        }

        batchRefresher.refresh(batch.getId());
        return saved;
    }

    /** 客服复核后补发积分（幂等：失败单已处置则拒绝，防止重复补发） */
    @Transactional
    public PointsIssue reissue(Long staffId, String issueNo, String note) {
        PointsIssue issue = lockPending(issueNo);
        if (pointsService.isFrozen(issue.getResidentId())) {
            throw BizException.conflict("居民积分账户处于冻结状态，不能补发；可改为纸质兑换券或先解冻账户");
        }

        int before = pointsService.balanceOf(issue.getResidentId());
        pointsService.reissue(issue.getResidentId(), issue.getExpectedPoints(),
                "ISSUE", issue.getIssueNo(), "客服补发 " + issue.getCategory()
                        + " " + issue.getWeightKg() + "kg 应发 " + issue.getExpectedPoints() + " 分");

        // 回填袋的实发标记
        findBag(issue).ifPresent(bag -> {
            bag.setPointsGranted(true);
            bag.setGrantedPoints(issue.getExpectedPoints());
            bagRepository.save(bag);
        });

        issue.setStatus(IssueStatus.REISSUED);
        issue.setReviewedBy(staffId);
        issue.setReviewNote(truncate(note, 500));
        issue.setReviewedAt(LocalDateTime.now());
        PointsIssue saved = issueRepository.save(issue);
        closeException(issue, staffId, "客服已补发 " + issue.getExpectedPoints() + " 积分，补发前可用 " + before);
        if (issue.getBatchId() != null) {
            batchRefresher.refresh(issue.getBatchId());
        }
        return saved;
    }

    /** 客服改为纸质兑换券（线下兑付，不进入积分账户） */
    @Transactional
    public PointsIssue convertToVoucher(Long staffId, String issueNo, String note) {
        PointsIssue issue = lockPending(issueNo);

        PaperVoucher voucher = voucherRepository.findByIssueNo(issue.getIssueNo()).orElseGet(() -> {
            PaperVoucher v = new PaperVoucher();
            v.setVoucherNo(NoGen.next("PV"));
            v.setResidentId(issue.getResidentId());
            v.setPointsValue(issue.getExpectedPoints());
            v.setCategory(issue.getCategory());
            v.setIssueNo(issue.getIssueNo());
            v.setBatchId(issue.getBatchId());
            v.setBatchNo(issue.getBatchNo());
            v.setBagNo(issue.getBagNo());
            return v;
        });
        voucher.setStatus(VoucherStatus.ISSUED);
        voucher.setIssuedBy(staffId);
        voucher.setIssueNote(truncate(note, 250));
        voucher.setIssuedAt(LocalDateTime.now());
        voucher = voucherRepository.save(voucher);

        // 转券不发积分：袋保持未发
        findBag(issue).ifPresent(bag -> {
            bag.setPointsGranted(false);
            bag.setGrantedPoints(0);
            bagRepository.save(bag);
        });

        issue.setStatus(IssueStatus.VOUCHERED);
        issue.setVoucherNo(voucher.getVoucherNo());
        issue.setReviewedBy(staffId);
        issue.setReviewNote(truncate(note, 500));
        issue.setReviewedAt(LocalDateTime.now());
        PointsIssue saved = issueRepository.save(issue);
        closeException(issue, staffId, "已改为纸质兑换券 " + voucher.getVoucherNo()
                + "，面值 " + voucher.getPointsValue() + " 分");
        if (issue.getBatchId() != null) {
            batchRefresher.refresh(issue.getBatchId());
        }
        return saved;
    }

    /** 客服驳回异常积分（经复核认定不应发放） */
    @Transactional
    public PointsIssue reject(Long staffId, String issueNo, String note) {
        PointsIssue issue = lockPending(issueNo);

        findBag(issue).ifPresent(bag -> {
            bag.setPointsGranted(false);
            bag.setGrantedPoints(0);
            bagRepository.save(bag);
        });

        issue.setStatus(IssueStatus.REJECTED);
        issue.setReviewedBy(staffId);
        issue.setReviewNote(truncate(note, 500));
        issue.setReviewedAt(LocalDateTime.now());
        PointsIssue saved = issueRepository.save(issue);
        closeException(issue, staffId, "异常积分经复核驳回，不予发放：" + note);
        if (issue.getBatchId() != null) {
            batchRefresher.refresh(issue.getBatchId());
        }
        return saved;
    }

    /** 纸质券线下兑付登记（社区点/管理员/客服） */
    @Transactional
    public PaperVoucher redeemVoucher(Long operatorId, String voucherNo, String note) {
        PaperVoucher voucher = voucherRepository.findByVoucherNo(voucherNo)
                .orElseThrow(() -> BizException.notFound("纸质兑换券不存在: " + voucherNo));
        if (voucher.getStatus() == VoucherStatus.VOID) {
            throw BizException.conflict("兑换券已作废");
        }
        if (voucher.getStatus() == VoucherStatus.REDEEMED) {
            throw BizException.conflict("兑换券已兑付，请勿重复兑付");
        }
        voucher.setStatus(VoucherStatus.REDEEMED);
        voucher.setRedeemedBy(operatorId);
        voucher.setRedeemedAt(LocalDateTime.now());
        voucher.setRedeemNote(truncate(note, 250));
        return voucherRepository.save(voucher);
    }

    private PointsIssue lockPending(String issueNo) {
        PointsIssue issue = issueRepository.lockByIssueNo(issueNo)
                .orElseThrow(() -> BizException.notFound("积分发放失败单不存在: " + issueNo));
        if (issue.getStatus() != IssueStatus.PENDING) {
            throw BizException.conflict("失败单已处置: " + issue.getStatus() + "，不能重复操作");
        }
        return issue;
    }

    private Optional<DrugBag> findBag(PointsIssue issue) {
        return issue.getBagId() == null ? Optional.empty() : bagRepository.findById(issue.getBagId());
    }

    private void closeException(PointsIssue issue, Long staffId, String resolution) {
        if (issue.getExceptionNo() == null) {
            return;
        }
        exceptionRepository.findByExceptionNo(issue.getExceptionNo()).ifPresent(ex -> {
            if (ex.getStatus() != ExceptionStatus.RESOLVED) {
                ex.setStatus(ExceptionStatus.RESOLVED);
                ex.setHandlerId(staffId);
                ex.setResolution(resolution);
                ex.setResolvedAt(LocalDateTime.now());
                exceptionRepository.save(ex);
            }
        });
    }

    @Transactional(readOnly = true)
    public PointsIssue get(String issueNo) {
        return issueRepository.findByIssueNo(issueNo)
                .orElseThrow(() -> BizException.notFound("积分发放失败单不存在: " + issueNo));
    }

    @Transactional(readOnly = true)
    public List<PointsIssue> list(IssueStatus status) {
        return status == null
                ? issueRepository.findAll(
                        org.springframework.data.domain.Sort.by("createdAt").descending())
                : issueRepository.findByStatusOrderByCreatedAtDesc(status);
    }

    @Transactional(readOnly = true)
    public List<PointsIssue> listForResident(Long residentId, IssueStatus status) {
        return status == null
                ? issueRepository.findByResidentIdOrderByCreatedAtDesc(residentId)
                : issueRepository.findByResidentIdAndStatusOrderByCreatedAtDesc(residentId, status);
    }

    @Transactional(readOnly = true)
    public List<PaperVoucher> vouchersForResident(Long residentId) {
        return voucherRepository.findByResidentIdOrderByIssuedAtDesc(residentId);
    }

    @Transactional(readOnly = true)
    public PaperVoucher getVoucher(String voucherNo) {
        return voucherRepository.findByVoucherNo(voucherNo)
                .orElseThrow(() -> BizException.notFound("纸质兑换券不存在: " + voucherNo));
    }

    /**
     * 居民积分台账：账户可用/冻结/状态 + 积分来源流水 + 发放失败单处理状态 +
     * 纸质兑换券 + 拒收冻结/追缴与处理依据，一处看清每笔积分从哪来、现在什么状态。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> residentPointsView(Long residentId) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("account", pointsService.accountView(residentId));
        view.put("transactions", pointsService.transactionsOf(residentId));
        view.put("issues", issueRepository.findByResidentIdOrderByCreatedAtDesc(residentId));
        view.put("vouchers", voucherRepository.findByResidentIdOrderByIssuedAtDesc(residentId));
        view.put("freezes", freezeRepository.findByResidentIdOrderByCreatedAtDesc(residentId));
        return view;
    }

    /**
     * 失败单详情：附处置所需的核对信息——居民是否已发生积分兑换（防重复补发参考）、
     * 关联拒收冻结单、纸质券、以及发放时的规则与账户快照。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(String issueNo) {
        PointsIssue issue = get(issueNo);
        List<PointsExchange> exchanges = exchangeRepository
                .findByResidentIdOrderByCreatedAtDesc(issue.getResidentId());
        long exchangedCount = exchanges.stream().filter(e -> e.getStatus() == ExchangeStatus.SUCCESS).count();
        boolean everExchanged = exchangedCount > 0;

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("issue", issue);
        view.put("residentEverExchanged", everExchanged);
        view.put("successfulExchangeCount", exchangedCount);
        view.put("recentExchanges", exchanges.stream().limit(5).toList());
        view.put("voucher", issue.getVoucherNo() == null ? null
                : voucherRepository.findByVoucherNo(issue.getVoucherNo()).orElse(null));
        view.put("relatedFreeze", issue.getRelatedFreezeId() == null ? null
                : freezeRepository.findById(issue.getRelatedFreezeId()).orElse(null));
        view.put("currentBalance", pointsService.balanceOf(issue.getResidentId()));
        view.put("accountFrozen", pointsService.isFrozen(issue.getResidentId()));
        return view;
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
