package com.community.medrecycle.service;

import com.community.medrecycle.config.BizException;
import com.community.medrecycle.config.NoGen;
import com.community.medrecycle.domain.*;
import com.community.medrecycle.domain.enums.BagPointsState;
import com.community.medrecycle.domain.enums.BagStatus;
import com.community.medrecycle.domain.enums.ExceptionType;
import com.community.medrecycle.domain.enums.HandoverStatus;
import com.community.medrecycle.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/** 环保交接：建单（上门量校验）、签收（双人判定）、完成（拒收处理） */
@Service
@RequiredArgsConstructor
public class HandoverService {

    private final HandoverOrderRepository orderRepository;
    private final HandoverItemRepository itemRepository;
    private final DrugBagRepository bagRepository;
    private final CommunitySiteRepository siteRepository;
    private final StorageCabinetRepository cabinetRepository;
    private final StorageEntryRepository entryRepository;
    private final ExceptionService exceptionService;
    private final PointsService pointsService;
    private final RecycleBatchRepository batchRepository;
    private final BatchRefresher batchRefresher;

    /** 建交接单：默认取站点全部暂存袋；未达环保公司上门量时拒绝（可 force 强制） */
    @Transactional
    public HandoverOrder create(Long siteId, String companyName, String destination,
                                List<String> bagNos, boolean force) {
        CommunitySite site = siteRepository.findById(siteId)
                .orElseThrow(() -> BizException.notFound("社区回收点不存在: " + siteId));

        List<DrugBag> bags;
        if (bagNos != null && !bagNos.isEmpty()) {
            bags = new ArrayList<>();
            for (String bagNo : bagNos) {
                DrugBag bag = bagRepository.findByBagNo(bagNo)
                        .orElseThrow(() -> BizException.notFound("封袋不存在: " + bagNo));
                if (bag.getStatus() != BagStatus.STORED || !bag.getSiteId().equals(siteId)) {
                    throw BizException.conflict("封袋 " + bagNo + " 不在该站点暂存中，状态: " + bag.getStatus());
                }
                bags.add(bag);
            }
        } else {
            bags = bagRepository.findBySiteIdAndStatus(siteId, BagStatus.STORED);
        }
        if (bags.isEmpty()) {
            throw BizException.conflict("站点暂存柜中没有可交接的药品袋");
        }

        double totalWeight = bags.stream().mapToDouble(DrugBag::getWeightKg).sum();
        if (totalWeight < site.getPickupThresholdKg() && !force) {
            throw BizException.conflict(String.format(
                    "未达到环保公司上门量：当前 %.1f kg < 阈值 %.1f kg（可传 force=true 强制发起）",
                    totalWeight, site.getPickupThresholdKg()));
        }

        boolean requiresDual = bags.stream().anyMatch(DrugBag::isHighRisk)
                || totalWeight >= site.getDualWeightThresholdKg();

        HandoverOrder order = new HandoverOrder();
        order.setOrderNo(NoGen.next("HO"));
        order.setSiteId(siteId);
        order.setCompanyName(companyName);
        order.setDestination(destination);
        order.setTotalWeightKg(totalWeight);
        order.setBagCount(bags.size());
        order.setRequiresDual(requiresDual);
        HandoverOrder saved = orderRepository.save(order);

        Set<Long> batchIds = new HashSet<>();
        for (DrugBag bag : bags) {
            HandoverItem item = new HandoverItem();
            item.setOrderId(saved.getId());
            item.setBagId(bag.getId());
            item.setBagNo(bag.getBagNo());
            item.setCategory(bag.getCategory());
            item.setWeightKg(bag.getWeightKg());
            itemRepository.save(item);

            bag.setStatus(BagStatus.HANDOVER_ASSIGNED);
            bagRepository.save(bag);
            batchIds.add(bag.getBatchId());
        }
        batchIds.forEach(batchRefresher::refresh);
        return saved;
    }

    /** 交出方签收：需要双人交接时必须两名不同签收人 */
    @Transactional
    public HandoverOrder sign(Long operatorId, String orderNo) {
        HandoverOrder order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> BizException.notFound("交接单不存在: " + orderNo));
        if (order.getStatus() != HandoverStatus.DRAFT) {
            throw BizException.conflict("交接单当前状态不可签收: " + order.getStatus());
        }
        if (order.getSignerA() == null) {
            order.setSignerA(operatorId);
        } else if (order.getSignerA().equals(operatorId)) {
            throw BizException.conflict("同一签收人不能重复签收");
        } else if (order.getSignerB() == null) {
            order.setSignerB(operatorId);
        } else {
            throw BizException.conflict("签收人已满");
        }

        boolean signedEnough = order.isRequiresDual()
                ? (order.getSignerA() != null && order.getSignerB() != null)
                : order.getSignerA() != null;
        if (signedEnough) {
            order.setStatus(HandoverStatus.IN_TRANSIT);
            order.setShippedAt(LocalDateTime.now());
        }
        return orderRepository.save(order);
    }

    public record ItemResult(String bagNo, boolean accepted, String rejectReason) {
    }

    /** 环保公司签收完成：逐袋确认，拒收袋回柜并生成 COMPANY_REJECT 异常 */
    @Transactional
    public HandoverOrder complete(String orderNo, String receiverName, String photoUrls,
                                  List<ItemResult> itemResults) {
        HandoverOrder order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> BizException.notFound("交接单不存在: " + orderNo));
        if (order.getStatus() != HandoverStatus.IN_TRANSIT) {
            throw BizException.conflict("交接单未处于待签收状态: " + order.getStatus());
        }
        Map<String, ItemResult> resultMap = new HashMap<>();
        if (itemResults != null) {
            for (ItemResult r : itemResults) {
                resultMap.put(r.bagNo(), r);
            }
        }

        List<HandoverItem> items = itemRepository.findByOrderId(order.getId());
        boolean anyRejected = false;
        Set<Long> batchIds = new HashSet<>();

        for (HandoverItem item : items) {
            DrugBag bag = bagRepository.findById(item.getBagId()).orElseThrow();
            ItemResult result = resultMap.get(item.getBagNo());
            boolean accepted = result == null || result.accepted();

            if (accepted) {
                item.setAccepted(true);
                bag.setStatus(BagStatus.HANDED_OVER);
                bagRepository.save(bag);
                releaseStorage(bag);
            } else {
                anyRejected = true;
                item.setAccepted(false);
                item.setRejectReason(result.rejectReason());
                // 拒收袋退回暂存，等待后续处理
                bag.setStatus(BagStatus.STORED);
                bagRepository.save(bag);
                RecycleBatch rejectedBatch = batchRepository.findById(bag.getBatchId()).orElseThrow();
                ExceptionRecord rejectEx = exceptionService.recordReject(rejectedBatch, bag,
                        result.rejectReason(), order.getCompanyName());
                // 已发积分随拒收进入冻结/追缴流程：可用部分冻结，已兑换部分登记欠费
                if (bag.isPointsGranted()
                        && bag.getPointsState() != BagPointsState.FROZEN
                        && bag.getPointsState() != BagPointsState.REVOKED) {
                    int[] split = pointsService.freezeRejectedPoints(
                            rejectedBatch.getResidentId(), bag.getPoints(), "BAG", bag.getBagNo(),
                            "环保公司拒收，积分冻结待追缴/解冻，袋 " + bag.getBagNo());
                    bag.setPointsState(BagPointsState.FROZEN);
                    bag.setFrozenPoints(split[0]);
                    bagRepository.save(bag);
                    rejectEx.setAffectedPoints(bag.getPoints());
                }
            }
            itemRepository.save(item);
            batchIds.add(bag.getBatchId());
        }

        order.setReceiverName(receiverName);
        order.setPhotoUrls(photoUrls);
        order.setStatus(anyRejected ? HandoverStatus.PARTIAL_REJECTED : HandoverStatus.COMPLETED);
        if (anyRejected) {
            order.setRejectNote("部分袋被环保公司拒收，详见异常记录");
        }
        order.setCompletedAt(LocalDateTime.now());
        HandoverOrder saved = orderRepository.save(order);

        batchIds.forEach(batchRefresher::refresh);
        return saved;
    }

    /** 袋出柜：关闭暂存记录并释放柜容量 */
    private void releaseStorage(DrugBag bag) {
        entryRepository.findByBagIdAndOutAtIsNull(bag.getId()).ifPresent(entry -> {
            entry.setOutAt(LocalDateTime.now());
            entryRepository.save(entry);
            StorageCabinet cabinet = cabinetRepository.lockById(entry.getCabinetId()).orElseThrow();
            cabinet.setUsedBags(Math.max(0, cabinet.getUsedBags() - 1));
            cabinet.setUsedWeightKg(Math.max(0, cabinet.getUsedWeightKg() - bag.getWeightKg()));
            cabinetRepository.save(cabinet);
        });
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(String orderNo) {
        HandoverOrder order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> BizException.notFound("交接单不存在: " + orderNo));
        List<HandoverItem> items = itemRepository.findByOrderId(order.getId());

        // 积分发放结果：按批次汇总
        Map<Long, Map<String, Object>> batchPoints = new LinkedHashMap<>();
        for (HandoverItem item : items) {
            DrugBag bag = bagRepository.findById(item.getBagId()).orElseThrow();
            Map<String, Object> bp = batchPoints.computeIfAbsent(bag.getBatchId(), k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("batchId", bag.getBatchId());
                m.put("totalPoints", 0);
                m.put("allGranted", true);
                return m;
            });
            bp.put("totalPoints", (int) bp.get("totalPoints") + (bag.isPointsGranted() ? bag.getPoints() : 0));
            bp.put("allGranted", (boolean) bp.get("allGranted") && bag.isPointsGranted());
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("order", order);
        detail.put("items", items);
        detail.put("pointsResult", new ArrayList<>(batchPoints.values()));
        return detail;
    }

    @Transactional(readOnly = true)
    public List<HandoverOrder> listBySite(Long siteId) {
        return orderRepository.findBySiteIdOrderByCreatedAtDesc(siteId);
    }
}
