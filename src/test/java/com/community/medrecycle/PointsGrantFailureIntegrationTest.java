package com.community.medrecycle;

import com.community.medrecycle.config.BizException;
import com.community.medrecycle.domain.*;
import com.community.medrecycle.domain.enums.*;
import com.community.medrecycle.repository.*;
import com.community.medrecycle.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 积分发放失败补发与拒收冻结追缴全链路：
 * 冻结致发放失败 -> 失败单证据链 -> 客服补发/纸券/驳回 -> 防重复补发（已兑换标志）；
 * 拒收已发积分 -> 冻结 -> 管理员追缴（含已兑换欠费）/申述解冻 -> 处理依据告知；
 * 居民积分来源视图。
 */
@SpringBootTest
class PointsGrantFailureIntegrationTest {

    @Autowired AppointmentService appointmentService;
    @Autowired BatchService batchService;
    @Autowired StorageService storageService;
    @Autowired HandoverService handoverService;
    @Autowired PointsService pointsService;
    @Autowired GrantFailureService grantFailureService;
    @Autowired ExceptionService exceptionService;
    @Autowired PointsSourceService pointsSourceService;
    @Autowired ReviewService reviewService;
    @Autowired AccountRepository accountRepository;
    @Autowired CommunitySiteRepository siteRepository;
    @Autowired StorageCabinetRepository cabinetRepository;
    @Autowired DrugBagRepository bagRepository;
    @Autowired GiftRepository giftRepository;
    @Autowired ReviewTaskRepository reviewTaskRepository;

    private Account newResident(String username) {
        Account a = new Account();
        a.setUsername(username);
        a.setPassword("resident123");
        a.setName(username);
        a.setRole(Role.RESIDENT);
        return accountRepository.save(a);
    }

    private DrugBag weigh(Account resident, Account recycler, Long siteId, DrugCategory category, double weightKg) {
        Appointment appointment = appointmentService.create(resident.getId(), siteId,
                category.name(), 1, false, false, false, false, null);
        RecycleBatch batch = batchService.checkIn(recycler.getId(), appointment.getAppointmentNo());
        batchService.weigh(recycler.getId(), batch.getBatchNo(), List.of(
                new BatchService.WeighItem(category, weightKg, null, "photo://x", false)));
        return bagRepository.findByBatchId(batch.getId()).get(0);
    }

    private StorageCabinet bigCabinet(Long siteId, String name) {
        StorageCabinet cabinet = new StorageCabinet();
        cabinet.setSiteId(siteId);
        cabinet.setName(name);
        cabinet.setCapacityBags(100);
        cabinet.setCapacityWeightKg(500);
        return cabinetRepository.save(cabinet);
    }

    private void rejectAtHandover(DrugBag bag, String reason) {
        Long siteId = bag.getSiteId();
        StorageCabinet cabinet = bigCabinet(siteId, "拒收测试柜-" + bag.getBagNo());
        storageService.putBags(cabinet.getId(), List.of(bag.getBagNo()));
        HandoverOrder order = handoverService.create(siteId, "绿源环保科技", "市危废处理中心",
                List.of(bag.getBagNo()), true);
        Account recycler = accountRepository.findByUsername("recycler1").orElseThrow();
        handoverService.sign(recycler.getId(), order.getOrderNo());
        handoverService.complete(order.getOrderNo(), "绿源-张司机", null,
                List.of(new HandoverService.ItemResult(bag.getBagNo(), false, reason)));
    }

    private ExceptionRecord rejectExceptionOf(String bagNo) {
        return exceptionService.list(null).stream()
                .filter(e -> e.getType() == ExceptionType.COMPANY_REJECT && bagNo.equals(e.getBagNo()))
                .findFirst().orElseThrow();
    }

    @Test
    void frozenAccountRecordsFailureThenReissueAndDetectsExchange() {
        Account resident = newResident("res_gf_reissue");
        Account recycler = accountRepository.findByUsername("recycler1").orElseThrow();
        Account service = accountRepository.findByUsername("service1").orElseThrow();
        Long siteId = siteRepository.findAll().get(0).getId();

        // 账户冻结期间完成称重：积分不能入账，落发放失败单
        pointsService.updateAccountStatus(resident.getId(), AccountStatus.FROZEN, "风控复核");
        int before = pointsService.balanceOf(resident.getId());
        DrugBag bag = weigh(resident, recycler, siteId, DrugCategory.TABLET, 2.0); // 2*10=20

        assertEquals(before, pointsService.balanceOf(resident.getId()), "冻结期间积分不得入账");
        assertEquals(BagPointsState.NOT_GRANTED, bagRepository.findById(bag.getId()).orElseThrow().getPointsState());

        List<PointsGrantFailure> pendings = grantFailureService.list(GrantFailureStatus.PENDING, resident.getId());
        assertEquals(1, pendings.size());
        PointsGrantFailure failure = pendings.get(0);
        assertEquals(20, failure.getPoints());
        assertEquals("TABLET", failure.getCategory());
        assertEquals(AccountStatus.FROZEN, failure.getAccountStatus());
        assertTrue(failure.getFailReason().contains("冻结"));
        assertTrue(failure.getPointsRuleSnapshot().contains("TABLET:10"), "失败单须留存积分规则快照");
        assertNotNull(failure.getBatchNo());
        assertNotNull(failure.getBagNo());
        assertEquals(1.0, failure.getPointsMultiplier(), 0.001);

        // 解冻后客服补发
        pointsService.updateAccountStatus(resident.getId(), AccountStatus.ACTIVE, "复核解除");
        PointsGrantFailure reissued = grantFailureService.reissue(service.getId(), failure.getFailureNo(), "核实无误");
        assertEquals(GrantFailureStatus.REISSUED, reissued.getStatus());
        assertEquals(before + 20, pointsService.balanceOf(resident.getId()));
        assertEquals(BagPointsState.GRANTED, bagRepository.findById(bag.getId()).orElseThrow().getPointsState());
        assertNotNull(reissued.getReissueTxnId());

        // 防重复补发：失败单已结
        BizException dup = assertThrows(BizException.class,
                () -> grantFailureService.reissue(service.getId(), failure.getFailureNo(), "再补一次"));
        assertEquals(409, dup.getStatus());

        // 补发后居民已兑换 -> 客服可见 exchangedAfterReissue=true，防止线下重复补发
        Gift gift = new Gift();
        gift.setName("补发验证礼品");
        gift.setCostPoints(20);
        gift.setStock(5);
        giftRepository.save(gift);
        assertEquals(ExchangeStatus.SUCCESS,
                pointsService.exchange(resident.getId(), gift.getId()).getStatus());

        Map<String, Object> detail = grantFailureService.detail(failure.getFailureNo());
        assertEquals(Boolean.TRUE, detail.get("exchangedAfterReissue"));
        assertEquals(Boolean.TRUE, detail.get("reissued"));
        assertNotNull(((Map<?, ?>) detail.get("account")).get("availablePoints"));
    }

    @Test
    void failureCanBeHandledAsPaperVoucher() {
        Account resident = newResident("res_gf_voucher");
        Account recycler = accountRepository.findByUsername("recycler1").orElseThrow();
        Account service = accountRepository.findByUsername("service1").orElseThrow();
        Long siteId = siteRepository.findAll().get(0).getId();

        pointsService.updateAccountStatus(resident.getId(), AccountStatus.FROZEN, "风控");
        int before = pointsService.balanceOf(resident.getId());
        DrugBag bag = weigh(resident, recycler, siteId, DrugCategory.LIQUID, 1.0); // 1*12=12
        PointsGrantFailure failure = grantFailureService.list(GrantFailureStatus.PENDING, resident.getId()).get(0);

        PointsGrantFailure handled = grantFailureService.paperVoucher(
                service.getId(), failure.getFailureNo(), "PV-2026-0001", "居民要求纸质凭证");
        assertEquals(GrantFailureStatus.PAPER_VOUCHER, handled.getStatus());
        assertEquals("PV-2026-0001", handled.getVoucherNo());
        assertEquals(before, pointsService.balanceOf(resident.getId()), "纸券不向电子账户入账");
        assertEquals(BagPointsState.PAPER_VOUCHER,
                bagRepository.findById(bag.getId()).orElseThrow().getPointsState());

        // 居民来源视图可见纸券号与状态
        Map<String, Object> sources = pointsSourceService.sourcesOf(resident.getId());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) sources.get("sources");
        Map<String, Object> row = list.stream().filter(s -> bag.getBagNo().equals(s.get("bagNo"))).findFirst().orElseThrow();
        assertEquals(BagPointsState.PAPER_VOUCHER, row.get("pointsState"));
        assertEquals("PV-2026-0001", ((Map<?, ?>) row.get("grantFailure")).get("voucherNo"));
        assertEquals(12, row.get("pointsPerKg"));
    }

    @Test
    void failureCanBeRejectedAndRequiresNote() {
        Account resident = newResident("res_gf_reject");
        Account recycler = accountRepository.findByUsername("recycler1").orElseThrow();
        Account service = accountRepository.findByUsername("service1").orElseThrow();
        Long siteId = siteRepository.findAll().get(0).getId();

        pointsService.updateAccountStatus(resident.getId(), AccountStatus.FROZEN, "风控");
        DrugBag bag = weigh(resident, recycler, siteId, DrugCategory.TABLET, 1.0);
        PointsGrantFailure failure = grantFailureService.list(GrantFailureStatus.PENDING, resident.getId()).get(0);

        assertThrows(BizException.class,
                () -> grantFailureService.reject(service.getId(), failure.getFailureNo(), " "));
        PointsGrantFailure rejected = grantFailureService.reject(
                service.getId(), failure.getFailureNo(), "核实为异常积分，不予发放");
        assertEquals(GrantFailureStatus.REJECTED, rejected.getStatus());
        assertEquals(BagPointsState.REVOKED, bagRepository.findById(bag.getId()).orElseThrow().getPointsState());
    }

    @Test
    void companyRejectFreezesGrantedPointsThenAdminClawsBackWithBasis() {
        Account resident = newResident("res_reject_clawback");
        Account recycler = accountRepository.findByUsername("recycler1").orElseThrow();
        Account admin = accountRepository.findByUsername("admin1").orElseThrow();
        Long siteId = siteRepository.findAll().get(0).getId();

        int before = pointsService.balanceOf(resident.getId());
        DrugBag bag = weigh(resident, recycler, siteId, DrugCategory.EXTERNAL, 2.0); // 2*6=12
        assertEquals(before + 12, pointsService.balanceOf(resident.getId()));

        // 环保公司拒收：已发积分自动冻结
        rejectAtHandover(bag, "管状包装不在接收名录");
        DrugBag frozenBag = bagRepository.findById(bag.getId()).orElseThrow();
        assertEquals(BagPointsState.FROZEN, frozenBag.getPointsState());
        assertEquals(12, frozenBag.getFrozenPoints());
        PointsAccount account = pointsService.accountOf(resident.getId());
        assertTrue(account.getFrozenPoints() >= 12);
        assertEquals(account.getBalance() - 12, account.availablePoints());

        ExceptionRecord rejectEx = rejectExceptionOf(bag.getBagNo());
        assertEquals(resident.getId(), rejectEx.getResidentId());
        assertEquals("EXTERNAL", rejectEx.getRejectCategory());
        assertEquals(ExceptionStatus.OPEN, rejectEx.getStatus());

        // 管理员追缴，必须登记依据，形成居民告知
        ExceptionRecord handled = exceptionService.handleReject(admin.getId(), rejectEx.getExceptionNo(),
                "CLAWBACK", "《社区回收拒收名录》2026版 第3.2条 管装外用药不予接收", null);
        assertEquals(ExceptionStatus.RESOLVED, handled.getStatus());
        assertEquals("CLAWBACK", handled.getResolutionAction());
        assertEquals(12, handled.getAffectedPoints());
        assertTrue(handled.getResidentNotice().contains("拒收"));
        assertTrue(handled.getResidentNotice().contains("第3.2条"));
        assertEquals(BagPointsState.REVOKED, bagRepository.findById(bag.getId()).orElseThrow().getPointsState());

        // 居民侧能看到拒收品类与处理依据
        ExceptionRecord mine = exceptionService.listByResident(resident.getId()).stream()
                .filter(e -> e.getExceptionNo().equals(rejectEx.getExceptionNo())).findFirst().orElseThrow();
        assertEquals("EXTERNAL", mine.getRejectCategory());
        assertNotNull(mine.getRegulationBasis());

        // 已办结不可重复处置
        assertThrows(BizException.class, () -> exceptionService.handleReject(admin.getId(),
                rejectEx.getExceptionNo(), "CLAWBACK", "依据", null));
        // 缺依据不允许处置
    }

    @Test
    void rejectHandlingRequiresBasis() {
        Account resident = newResident("res_reject_basis");
        Account recycler = accountRepository.findByUsername("recycler1").orElseThrow();
        Account admin = accountRepository.findByUsername("admin1").orElseThrow();
        Long siteId = siteRepository.findAll().get(0).getId();

        DrugBag bag = weigh(resident, recycler, siteId, DrugCategory.EXTERNAL, 1.0);
        rejectAtHandover(bag, "不在接收范围");
        ExceptionRecord rejectEx = rejectExceptionOf(bag.getBagNo());

        BizException e = assertThrows(BizException.class, () -> exceptionService.handleReject(
                admin.getId(), rejectEx.getExceptionNo(), "CLAWBACK", "  ", null));
        assertEquals(400, e.getStatus());
    }

    @Test
    void exchangedPointsOnRejectBecomeDebtAndBlockFurtherExchange() {
        Account resident = newResident("res_reject_debt");
        Account recycler = accountRepository.findByUsername("recycler1").orElseThrow();
        Long siteId = siteRepository.findAll().get(0).getId();

        DrugBag bag = weigh(resident, recycler, siteId, DrugCategory.TABLET, 5.0); // 50 分
        // 拒收完成前先把积分兑换掉（袋已分配交接单，积分仍在账户）
        StorageCabinet cabinet = bigCabinet(siteId, "欠费路径柜");
        storageService.putBags(cabinet.getId(), List.of(bag.getBagNo()));
        HandoverOrder order = handoverService.create(siteId, "绿源环保科技", "市危废处理中心",
                List.of(bag.getBagNo()), true);
        handoverService.sign(recycler.getId(), order.getOrderNo());

        Gift gift = new Gift();
        gift.setName("欠费路径礼品");
        gift.setCostPoints(50);
        gift.setStock(5);
        giftRepository.save(gift);
        assertEquals(ExchangeStatus.SUCCESS, pointsService.exchange(resident.getId(), gift.getId()).getStatus());
        assertEquals(0, pointsService.accountOf(resident.getId()).availablePoints());

        // 环保公司拒收：可冻结额为 0，50 分全部登记欠费追缴
        handoverService.complete(order.getOrderNo(), "绿源-张司机", null,
                List.of(new HandoverService.ItemResult(bag.getBagNo(), false, "混入非回收品类")));
        DrugBag rejected = bagRepository.findById(bag.getId()).orElseThrow();
        assertEquals(BagPointsState.FROZEN, rejected.getPointsState());
        assertEquals(0, rejected.getFrozenPoints());
        assertTrue(pointsService.accountOf(resident.getId()).getDebtPoints() >= 50);

        // 欠费未冲抵前禁止兑换
        PointsExchange blocked = pointsService.exchange(resident.getId(), gift.getId());
        assertEquals(ExchangeStatus.FAILED, blocked.getStatus());
        assertTrue(blocked.getFailReason().contains("欠费"));

        // 新一笔回收积分优先冲抵欠费，账户不新增可用余额
        int before = pointsService.balanceOf(resident.getId());
        weigh(resident, recycler, siteId, DrugCategory.TABLET, 5.0);
        assertEquals(before, pointsService.balanceOf(resident.getId()), "新积分应全部冲抵欠费");
        assertEquals(0, pointsService.accountOf(resident.getId()).getDebtPoints(), "欠费应被冲抵完毕");
    }

    @Test
    void appealAcceptedUnfreezesPoints() {
        Account resident = newResident("res_reject_unfreeze");
        Account recycler = accountRepository.findByUsername("recycler1").orElseThrow();
        Account admin = accountRepository.findByUsername("admin1").orElseThrow();
        Long siteId = siteRepository.findAll().get(0).getId();

        int before = pointsService.balanceOf(resident.getId());
        DrugBag bag = weigh(resident, recycler, siteId, DrugCategory.LIQUID, 1.0); // 12 分
        rejectAtHandover(bag, "误判为非回收品类");
        assertTrue(pointsService.accountOf(resident.getId()).getFrozenPoints() >= 12);

        ExceptionRecord rejectEx = rejectExceptionOf(bag.getBagNo());
        ExceptionRecord handled = exceptionService.handleReject(admin.getId(), rejectEx.getExceptionNo(),
                "UNFREEZE", "《社区积分争议处理办法》第5条 复核为可回收品类", "经复核，该批次为可回收液体药，积分恢复");
        assertEquals("UNFREEZE", handled.getResolutionAction());
        assertEquals(BagPointsState.GRANTED, bagRepository.findById(bag.getId()).orElseThrow().getPointsState());
        assertEquals(before + 12, pointsService.balanceOf(resident.getId()));
        assertEquals(0, bagRepository.findById(bag.getId()).orElseThrow().getFrozenPoints());
        assertTrue(handled.getResidentNotice().contains("复核"));
    }

    @Test
    void reviewRejectAutoClosesPendingGrantFailure() {
        Account resident = newResident("res_gf_review_reject");
        Account recycler = accountRepository.findByUsername("recycler1").orElseThrow();
        Account reviewer = accountRepository.findByUsername("reviewer1").orElseThrow();
        Long siteId = siteRepository.findAll().get(0).getId();

        pointsService.updateAccountStatus(resident.getId(), AccountStatus.FROZEN, "风控");
        DrugBag bag = weigh(resident, recycler, siteId, DrugCategory.TABLET, 1.0);
        PointsGrantFailure failure = grantFailureService.list(GrantFailureStatus.PENDING, resident.getId()).get(0);

        // 夹带未过期药 -> 袋退回复核；复核员拒绝
        exceptionService.raise(null, bag.getBagNo(), ExceptionType.NON_EXPIRED_DRUG, "发现未过期药品");
        ReviewTask task = reviewTaskRepositoryFind(bag.getId());
        reviewService.decide(reviewer.getId(), task.getTaskNo(), false, "退回居民");

        assertEquals(GrantFailureStatus.REJECTED,
                grantFailureService.get(failure.getFailureNo()).getStatus(),
                "复核拒绝应同时驳回待处理发放失败单，防止客服误补发");
        assertEquals(BagPointsState.REVOKED, bagRepository.findById(bag.getId()).orElseThrow().getPointsState());
    }

    private ReviewTask reviewTaskRepositoryFind(Long bagId) {
        return reviewTaskRepository.findByBagIdAndStatus(bagId, ReviewStatus.PENDING).get(0);
    }

    @Test
    void residentSourcesViewShowsRuleStateAndRejectInfo() {
        Account resident = newResident("res_sources");
        Account recycler = accountRepository.findByUsername("recycler1").orElseThrow();
        Long siteId = siteRepository.findAll().get(0).getId();

        DrugBag bag = weigh(resident, recycler, siteId, DrugCategory.ANTIBIOTIC, 2.0); // 30 分
        rejectAtHandover(bag, "瓶身破损液体污染");

        Map<String, Object> sources = pointsSourceService.sourcesOf(resident.getId());
        assertNotNull(sources.get("account"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) sources.get("sources");
        Map<String, Object> row = list.stream().filter(s -> bag.getBagNo().equals(s.get("bagNo"))).findFirst().orElseThrow();
        assertEquals("ANTIBIOTIC", ((DrugCategory) row.get("category")).name());
        assertEquals(15, row.get("pointsPerKg"));
        assertEquals(30, row.get("points"));
        assertEquals(BagPointsState.FROZEN, row.get("pointsState"));
        Map<?, ?> rejectInfo = (Map<?, ?>) row.get("rejectInfo");
        assertEquals("ANTIBIOTIC", rejectInfo.get("rejectCategory"));
    }
}
