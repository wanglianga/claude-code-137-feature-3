package com.community.medrecycle.config;

import com.community.medrecycle.domain.*;
import com.community.medrecycle.domain.enums.DrugCategory;
import com.community.medrecycle.domain.enums.Role;
import com.community.medrecycle.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/** 演示数据：仅当账号表为空时写入 */
@Component
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    private final AccountRepository accountRepository;
    private final CommunitySiteRepository siteRepository;
    private final StorageCabinetRepository cabinetRepository;
    private final PointsRuleRepository pointsRuleRepository;
    private final GiftRepository giftRepository;
    private final ActivityDayRepository activityDayRepository;
    private final PointsAccountRepository pointsAccountRepository;

    @Value("${app.seed-demo-data:true}")
    private boolean seedDemoData;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!seedDemoData || accountRepository.count() > 0) {
            return;
        }

        // 社区回收点
        CommunitySite site1 = site("阳光社区回收点", "阳光路 12 号社区服务中心", 0.8, 50, 30, 20);
        CommunitySite site2 = site("河畔社区回收点", "滨河大道 3 号", 0.8, 40, 30, 15);

        // 暂存柜
        cabinet(site1.getId(), "阳光-A 综合柜", 100, 80);
        cabinet(site1.getId(), "阳光-B 高危专柜", 40, 30);
        cabinet(site2.getId(), "河畔-A 综合柜", 60, 50);

        // 积分规则（每公斤积分；精神类/注射器为高风险需人工复核）
        rule(DrugCategory.TABLET, 10, false, "普通片剂/胶囊");
        rule(DrugCategory.ANTIBIOTIC, 15, false, "抗生素类");
        rule(DrugCategory.PSYCHOTROPIC, 20, true, "精神类药品（高风险，须复核）");
        rule(DrugCategory.SYRINGE, 8, true, "注射器/针剂（高风险，须复核）");
        rule(DrugCategory.EXTERNAL, 6, false, "外用膏药/软膏");
        rule(DrugCategory.LIQUID, 12, false, "口服液/糖浆等液体药");

        // 礼品
        gift("环保购物袋", 50, 100);
        gift("免洗洗手液", 120, 50);
        gift("社区体检券", 500, 10);

        // 测试账号（演示环境使用明文口令）
        Account admin = account("admin1", "admin123", "社区管理员-王芳", Role.ADMIN, site1.getId());
        account("admin2", "admin123", "社区管理员-李强", Role.ADMIN, site2.getId());
        account("recycler1", "recycler123", "回收员-赵磊", Role.RECYCLER, site1.getId());
        account("recycler2", "recycler123", "回收员-孙敏", Role.RECYCLER, site2.getId());
        account("reviewer1", "reviewer123", "复核员-周药师", Role.REVIEWER, null);
        account("service1", "service123", "客服-林悦", Role.SERVICE, null);
        Account resident1 = account("resident1", "resident123", "居民-陈晓", Role.RESIDENT, null);
        Account resident2 = account("resident2", "resident123", "居民-刘洋", Role.RESIDENT, null);

        // 积分账户（resident1 预置 100 分便于演示兑换）
        pointsAccount(resident1.getId(), 100);
        pointsAccount(resident2.getId(), 0);

        // 社区活动日：大批量回收专场，积分 1.5 倍
        ActivityDay day = new ActivityDay();
        day.setSiteId(site1.getId());
        day.setName("秋季环保回收日");
        day.setActivityDate(LocalDate.now().plusDays(3));
        day.setPointsMultiplier(1.5);
        day.setMaxAppointments(200);
        activityDayRepository.save(day);
    }

    private CommunitySite site(String name, String address, double warnRatio, double pickupThresholdKg,
                               int overdueDays, double dualWeightThresholdKg) {
        CommunitySite site = new CommunitySite();
        site.setName(name);
        site.setAddress(address);
        site.setWarnRatio(warnRatio);
        site.setPickupThresholdKg(pickupThresholdKg);
        site.setOverdueDays(overdueDays);
        site.setDualWeightThresholdKg(dualWeightThresholdKg);
        return siteRepository.save(site);
    }

    private void cabinet(Long siteId, String name, int capacityBags, double capacityWeightKg) {
        StorageCabinet cabinet = new StorageCabinet();
        cabinet.setSiteId(siteId);
        cabinet.setName(name);
        cabinet.setCapacityBags(capacityBags);
        cabinet.setCapacityWeightKg(capacityWeightKg);
        cabinetRepository.save(cabinet);
    }

    private void rule(DrugCategory category, int pointsPerKg, boolean highRisk, String description) {
        PointsRule rule = new PointsRule();
        rule.setCategory(category);
        rule.setPointsPerKg(pointsPerKg);
        rule.setHighRisk(highRisk);
        rule.setDescription(description);
        pointsRuleRepository.save(rule);
    }

    private void gift(String name, int costPoints, int stock) {
        Gift gift = new Gift();
        gift.setName(name);
        gift.setCostPoints(costPoints);
        gift.setStock(stock);
        giftRepository.save(gift);
    }

    private Account account(String username, String password, String name, Role role, Long siteId) {
        Account account = new Account();
        account.setUsername(username);
        account.setPassword(password);
        account.setName(name);
        account.setRole(role);
        account.setSiteId(siteId);
        return accountRepository.save(account);
    }

    private void pointsAccount(Long residentId, int balance) {
        PointsAccount account = new PointsAccount();
        account.setResidentId(residentId);
        account.setBalance(balance);
        pointsAccountRepository.save(account);
    }
}
