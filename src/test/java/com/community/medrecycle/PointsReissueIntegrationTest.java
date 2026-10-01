package com.community.medrecycle;

import com.community.medrecycle.config.BizException;
import com.community.medrecycle.domain.*;
import com.community.medrecycle.domain.enums.*;
import com.community.medrecycle.repository.*;
import com.community.medrecycle.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 积分发放失败补发 + 拒收冻结/追缴：
 * 账户冻结导致发放失败落单（含规则/批次/账户快照）、客服补发/纸质券/驳回、
 * 防重复补发、拒收已发积分冻结与追缴欠分自动抵扣、申诉解冻退回、已兑换标记。
 */
@SpringBootTest
class PointsReissueIntegrationTest {

    @Autowired PointsService pointsService;
    @Autowired PointsIssueService issueService;
    @Autowired FreezeService freezeService;
    @Autowired AppointmentService appointmentService;
    @Autowired BatchService batchService;
    @Autowired StorageService storageService;
    @Autowired HandoverService handoverService;
    @Autowired AccountRepository accountRepository;
    @Autowired CommunitySiteRepository siteRepository;
    @Autowired StorageCabinetRepository cabinetRepository;
    @Autowired DrugBagRepository bagRepository;
    @Autowired GiftRepository giftRepository;
    @Autowired PointsIssueRepository issueRepository;
    @Autowired PointsFreezeRepository freezeRepository;
    @Autowired PaperVoucherRepository voucherRepository;
    @Autowired ExceptionRecordRepository exceptionRepository;

    @AfterEach
    void unfreezeAccounts() {
        // 避免冻结状态污染共享测试库中的其他用例
        accountRepository.findByUsername("resident1").ifPresent(a -> pointsService.unfreezeAccount(a.getId()));
        accountRepository.findByUsername("resident2").ifPresent(a -> pointsService.unfreezeAccount(a.getId()));
    }

    private Account user(String username) {
        return accountRepository.findByUsername(username).orElseThrow();
    }

    /** 创建一次性居民账户（余额 0），避免用例间共享余额状态 */
    private Account newResident() {
        Account a = new Account();
        a.setUsername("rtest-" + java.util.UUID.randomUUID());
        a.setPassword("resident123");
        a.setName("临时居民");
        a.setRole(Role.RESIDENT);
        return accountRepository.save(a);
    }

    private Gift gift(int cost) {
        Gift g = new Gift();
        g.setName("积分流程测试礼品-" + cost + "-" + java.util.UUID.randomUUID());
        g.setCostPoints(cost);
        g.setStock(100);
        return giftRepository.save(g);
    }

    /** 自建专用大容量暂存柜，避免与其他用例共享柜容量 */
    private StorageCabinet newCabinet(Long siteId) {
        StorageCabinet c = new StorageCabinet();
        c.setSiteId(siteId);
        c.setName("积分流程测试柜-" + java.util.UUID.randomUUID());
        c.setCapacityBags(100);
        c.setCapacityWeightKg(500.0);
        return cabinetRepository.save(c);
    }

    /** 非高风险品类称重一袋（TABLET 10 分/kg，无活动日倍率） */
    private DrugBag weighTablet(Account resident, Account recycler, Long siteId, double kg) {
        Appointment appointment = appointmentService.create(resident.getId(), siteId,
                "片剂", 1, false, false, false, false, null);
        RecycleBatch batch = batchService.checkIn(recycler.getId(), appointment.getAppointmentNo());
        batchService.weigh(recycler.getId(), batch.getBatchNo(), List.of(
                new BatchService.WeighItem(DrugCategory.TABLET, kg, null, "photo://x", false)));
        return bagRepository.findByBatchId(batch.getId()).get(0);
    }

    // 1. 账户冻结导致发放失败落单，解冻后补发，重复补发被拒
    @Test
    void grantFailureRecordedThenReissuedIdempotently() {
        Account resident = user("resident1");
        Account recycler = user("recycler1");
        Account service = user("service1");
        Long siteId = siteRepository.findAll().get(0).getId();
        pointsService.unfreezeAccount(resident.getId());

        // 居民账户被冻结 -> 称重后积分无法发放
        pointsService.freezeAccount(resident.getId());
        int before = pointsService.balanceOf(resident.getId());
        DrugBag bag = weighTablet(resident, recycler, siteId, 2.0); // 应发 20

        assertEquals(before, pointsService.balanceOf(resident.getId()), "冻结账户不应入账");
        PointsIssue issue = issueRepository.findByBagNo(bag.getBagNo()).orElseThrow();
        assertEquals(IssueStatus.PENDING, issue.getStatus());
        assertEquals(20, issue.getExpectedPoints());
        assertEquals(GrantFailReason.ACCOUNT_FROZEN, issue.getFailReason());
        assertEquals(AccountStatus.FROZEN, issue.getAccountStatusAtFailure());
        assertTrue(issue.getPointsRuleSnapshot().contains("TABLET:10"), "失败单须固化积分规则快照");
        assertEquals(1.0, issue.getPointsMultiplier(), 0.0001);
        assertNotNull(issue.getBatchNo());
        assertEquals(0, bagRepository.findById(bag.getId()).orElseThrow().getGrantedPoints());
        // 自动登记发放失败异常
        ExceptionRecord ex = exceptionRepository.findByExceptionNo(issue.getExceptionNo()).orElseThrow();
        assertEquals(ExceptionType.POINTS_GRANT_FAILED, ex.getType());

        // 冻结期间补发被拒
        BizException blocked = assertThrows(BizException.class,
                () -> issueService.reissue(service.getId(), issue.getIssueNo(), "尝试补发"));
        assertEquals(409, blocked.getStatus());

        // 解冻后客服复核补发
        pointsService.unfreezeAccount(resident.getId());
        PointsIssue reissued = issueService.reissue(service.getId(), issue.getIssueNo(), "核实为系统故障，予以补发");
        assertEquals(IssueStatus.REISSUED, reissued.getStatus());
        assertEquals(before + 20, pointsService.balanceOf(resident.getId()));
        assertTrue(bagRepository.findById(bag.getId()).orElseThrow().isPointsGranted());
        assertEquals(20, bagRepository.findById(bag.getId()).orElseThrow().getGrantedPoints());
        assertEquals(ExceptionStatus.RESOLVED,
                exceptionRepository.findByExceptionNo(issue.getExceptionNo()).orElseThrow().getStatus());

        // 防重复补发：失败单已处置 -> 409
        BizException dup = assertThrows(BizException.class,
                () -> issueService.reissue(service.getId(), issue.getIssueNo(), "再次补发"));
        assertEquals(409, dup.getStatus());
        assertEquals(before + 20, pointsService.balanceOf(resident.getId()), "不得重复增加积分");
    }

    // 2. 改为纸质兑换券，不入积分账户，可线下兑付且防重复兑付
    @Test
    void convertToPaperVoucherAndRedeem() {
        Account resident = user("resident1");
        Account recycler = user("recycler1");
        Account service = user("service1");
        Account admin = user("admin1");
        Long siteId = siteRepository.findAll().get(0).getId();

        pointsService.freezeAccount(resident.getId());
        int before = pointsService.balanceOf(resident.getId());
        DrugBag bag = weighTablet(resident, recycler, siteId, 3.0); // 应发 30
        PointsIssue issue = issueRepository.findByBagNo(bag.getBagNo()).orElseThrow();

        PointsIssue vouchered = issueService.convertToVoucher(service.getId(), issue.getIssueNo(), "账户冻结，改发纸质券");
        assertEquals(IssueStatus.VOUCHERED, vouchered.getStatus());
        assertEquals(before, pointsService.balanceOf(resident.getId()), "转券不进入积分账户");
        assertEquals(0, bagRepository.findById(bag.getId()).orElseThrow().getGrantedPoints());

        PaperVoucher voucher = voucherRepository.findByIssueNo(issue.getIssueNo()).orElseThrow();
        assertEquals(30, voucher.getPointsValue());
        assertEquals(VoucherStatus.ISSUED, voucher.getStatus());
        assertTrue(issueService.vouchersForResident(resident.getId()).stream()
                .anyMatch(v -> v.getVoucherNo().equals(voucher.getVoucherNo())));

        PaperVoucher redeemed = issueService.redeemVoucher(admin.getId(), voucher.getVoucherNo(), "社区点已兑付");
        assertEquals(VoucherStatus.REDEEMED, redeemed.getStatus());
        BizException dup = assertThrows(BizException.class,
                () -> issueService.redeemVoucher(admin.getId(), voucher.getVoucherNo(), "重复兑付"));
        assertEquals(409, dup.getStatus());
    }

    // 3. 客服驳回异常积分：不发放，异常办结
    @Test
    void rejectAbnormalPoints() {
        Account resident = user("resident1");
        Account recycler = user("recycler1");
        Account service = user("service1");
        Long siteId = siteRepository.findAll().get(0).getId();

        pointsService.freezeAccount(resident.getId());
        int before = pointsService.balanceOf(resident.getId());
        DrugBag bag = weighTablet(resident, recycler, siteId, 1.0); // 应发 10
        PointsIssue issue = issueRepository.findByBagNo(bag.getBagNo()).orElseThrow();

        PointsIssue rejected = issueService.reject(service.getId(), issue.getIssueNo(), "复核认定该袋不符合发放条件");
        assertEquals(IssueStatus.REJECTED, rejected.getStatus());
        assertEquals(before, pointsService.balanceOf(resident.getId()));
        assertEquals(0, bagRepository.findById(bag.getId()).orElseThrow().getGrantedPoints());
        assertEquals(ExceptionStatus.RESOLVED,
                exceptionRepository.findByExceptionNo(issue.getExceptionNo()).orElseThrow().getStatus());
    }

    // 4. 拒收已发积分：余额已被兑换花光 -> 形成追缴欠分，后续入账自动抵扣直至偿清
    @Test
    void rejectedPointsRecoveredViaFutureCreditsWhenBalanceInsufficient() {
        Account resident = newResident();
        Account recycler = user("recycler1");
        Account admin = user("admin1");
        Long siteId = siteRepository.findAll().get(0).getId();
        StorageCabinet cabinet = newCabinet(siteId);

        // 称重 5kg 发放 50，随后兑换花光，制造拒收时可用余额不足
        DrugBag bag = weighTablet(resident, recycler, siteId, 5.0);
        assertEquals(50, pointsService.balanceOf(resident.getId()));
        PointsExchange exchange = pointsService.exchange(resident.getId(), gift(50).getId());
        assertEquals(ExchangeStatus.SUCCESS, exchange.getStatus());
        assertEquals(0, pointsService.balanceOf(resident.getId()));

        // 入柜 -> 交接 -> 环保公司拒收
        storageService.putBags(cabinet.getId(), List.of(bag.getBagNo()));
        HandoverOrder order = handoverService.create(siteId, "绿源环保科技", "市危废处理中心",
                List.of(bag.getBagNo()), true);
        handoverService.sign(recycler.getId(), order.getOrderNo());
        String rejectReason = "片剂混装不在接收范围";
        handoverService.complete(order.getOrderNo(), "绿源-张司机", null,
                List.of(new HandoverService.ItemResult(bag.getBagNo(), false, rejectReason)));

        PointsFreeze freeze = freezeRepository.findByBagNo(bag.getBagNo()).orElseThrow();
        assertEquals(50, freeze.getTotalPoints());
        assertEquals(0, freeze.getFrozenPoints(), "可用余额为 0，无法当场冻结");
        assertEquals(50, freeze.getOutstandingPoints(), "不足部分形成追缴欠分");
        assertEquals(FreezeStatus.FROZEN, freeze.getStatus());
        assertEquals(rejectReason, freeze.getRejectReason());

        // 管理员登记拒收品类与处理依据，向居民说明
        freezeService.recordBasis(admin.getId(), freeze.getFreezeNo(), "TABLET",
                "依据《社区过期药品回收积分管理办法》第 12 条，拒收品类已发积分应予冻结追缴");
        assertEquals("TABLET", freezeService.get(freeze.getFreezeNo()).getRejectedCategory());
        assertNotNull(freezeService.get(freeze.getFreezeNo()).getHandlingBasis());
        // 居民可查看本人冻结与依据
        assertTrue(freezeService.listForResident(resident.getId()).stream()
                .anyMatch(f -> f.getFreezeNo().equals(freeze.getFreezeNo())));

        // 主动追缴但可用余额为 0：欠分不变
        freezeService.recover(admin.getId(), freeze.getFreezeNo(), "催缴");
        assertEquals(50, freezeService.get(freeze.getFreezeNo()).getOutstandingPoints());

        // 又回收一袋发放 30：入账积分自动抵扣欠分 30，居民可用余额仍为 0
        weighTablet(resident, recycler, siteId, 3.0);
        PointsFreeze after30 = freezeService.get(freeze.getFreezeNo());
        assertEquals(20, after30.getOutstandingPoints());
        assertEquals(30, after30.getRecoveredPoints());
        assertEquals(0, pointsService.balanceOf(resident.getId()));
        assertEquals(FreezeStatus.FROZEN, after30.getStatus());

        // 再发放 20：欠分偿清，冻结单追缴核销
        weighTablet(resident, recycler, siteId, 2.0);
        PointsFreeze cleared = freezeService.get(freeze.getFreezeNo());
        assertEquals(FreezeStatus.RECOVERED, cleared.getStatus());
        assertEquals(0, cleared.getOutstandingPoints());
        assertEquals(50, cleared.getRecoveredPoints());
        assertEquals(0, pointsService.balanceOf(resident.getId()));
    }

    // 5. 拒收时可用余额充足 -> 当场冻结；管理员登记依据后申诉成立解冻退回
    @Test
    void rejectedPointsFrozenThenReleasedAfterAppeal() {
        Account resident = newResident();
        Account recycler = user("recycler1");
        Account admin = user("admin1");
        Long siteId = siteRepository.findAll().get(0).getId();
        StorageCabinet cabinet = newCabinet(siteId);

        int before = pointsService.balanceOf(resident.getId());
        DrugBag bag = weighTablet(resident, recycler, siteId, 4.0); // 发放 40，不兑换
        assertEquals(before + 40, pointsService.balanceOf(resident.getId()));

        storageService.putBags(cabinet.getId(), List.of(bag.getBagNo()));
        HandoverOrder order = handoverService.create(siteId, "绿源环保科技", "市危废处理中心",
                List.of(bag.getBagNo()), true);
        handoverService.sign(recycler.getId(), order.getOrderNo());
        handoverService.complete(order.getOrderNo(), "绿源-张司机", null,
                List.of(new HandoverService.ItemResult(bag.getBagNo(), false, "外箱破损")));

        PointsFreeze freeze = freezeRepository.findByBagNo(bag.getBagNo()).orElseThrow();
        assertEquals(40, freeze.getFrozenPoints());
        assertEquals(0, freeze.getOutstandingPoints());
        assertEquals(before, pointsService.balanceOf(resident.getId()), "可用余额被扣减转入冻结");
        assertEquals(40, pointsService.accountView(resident.getId()).get("frozenBalance"));

        freezeService.recordBasis(admin.getId(), freeze.getFreezeNo(), "TABLET", "拒收处理依据说明");

        // 申诉成立：解除冻结，积分退回可用余额
        PointsFreeze released = freezeService.release(admin.getId(), freeze.getFreezeNo(), "复核为环保公司误拒收，退回积分");
        assertEquals(FreezeStatus.RELEASED, released.getStatus());
        assertEquals(before + 40, pointsService.balanceOf(resident.getId()));
        assertEquals(0, pointsService.accountView(resident.getId()).get("frozenBalance"));
    }

    // 6. 客服详情可见居民是否已发生兑换（防重复补发参考），且失败单列表可查
    @Test
    void issueDetailShowsExchangeHistoryAndResidentLedger() {
        Account resident = newResident();
        Account recycler = user("recycler1");
        Long siteId = siteRepository.findAll().get(0).getId();

        // 先成功兑换一次
        pointsService.grant(resident.getId(), 500, "TEST", "ledger-init", "测试预置");
        assertEquals(ExchangeStatus.SUCCESS,
                pointsService.exchange(resident.getId(), gift(50).getId()).getStatus());

        // 冻结后制造一笔发放失败
        pointsService.freezeAccount(resident.getId());
        DrugBag bag = weighTablet(resident, recycler, siteId, 1.0);
        PointsIssue issue = issueRepository.findByBagNo(bag.getBagNo()).orElseThrow();

        // 客服列表能看到该待办
        assertTrue(issueService.list(IssueStatus.PENDING).stream()
                .anyMatch(i -> i.getIssueNo().equals(issue.getIssueNo())));

        Map<String, Object> detail = issueService.detail(issue.getIssueNo());
        assertEquals(Boolean.TRUE, detail.get("residentEverExchanged"));
        assertTrue(((Number) detail.get("successfulExchangeCount")).intValue() >= 1);
        assertNotNull(detail.get("recentExchanges"));

        // 居民台账含失败单与账户视图，处理状态可查
        Map<String, Object> ledger = issueService.residentPointsView(resident.getId());
        assertNotNull(ledger.get("account"));
        @SuppressWarnings("unchecked")
        List<PointsIssue> issues = (List<PointsIssue>) ledger.get("issues");
        assertTrue(issues.stream().anyMatch(i -> i.getIssueNo().equals(issue.getIssueNo())
                && i.getStatus() == IssueStatus.PENDING));
    }
}
