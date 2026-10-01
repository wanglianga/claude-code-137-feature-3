package com.community.medrecycle.service;

import com.community.medrecycle.config.BizException;
import com.community.medrecycle.domain.DrugBag;
import com.community.medrecycle.domain.PointsGrantFailure;
import com.community.medrecycle.domain.RecycleBatch;
import com.community.medrecycle.domain.ReviewTask;
import com.community.medrecycle.domain.enums.BagPointsState;
import com.community.medrecycle.domain.enums.BagStatus;
import com.community.medrecycle.domain.enums.GrantFailureStatus;
import com.community.medrecycle.domain.enums.ReviewStatus;
import com.community.medrecycle.repository.DrugBagRepository;
import com.community.medrecycle.repository.PointsGrantFailureRepository;
import com.community.medrecycle.repository.RecycleBatchRepository;
import com.community.medrecycle.repository.ReviewTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 人工复核：高风险药品复核通过才发积分、才允许入柜 */
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewTaskRepository reviewTaskRepository;
    private final DrugBagRepository bagRepository;
    private final RecycleBatchRepository batchRepository;
    private final PointsGrantFailureRepository grantFailureRepository;
    private final BatchService batchService;
    private final PointsService pointsService;
    private final BatchRefresher batchRefresher;

    @Transactional(readOnly = true)
    public List<ReviewTask> list(ReviewStatus status) {
        return status == null
                ? reviewTaskRepository.findAll()
                : reviewTaskRepository.findByStatusOrderByCreatedAtAsc(status);
    }

    @Transactional
    public ReviewTask decide(Long reviewerId, String taskNo, boolean approve, String note) {
        ReviewTask task = reviewTaskRepository.findByTaskNo(taskNo)
                .orElseThrow(() -> BizException.notFound("复核任务不存在: " + taskNo));
        if (task.getStatus() != ReviewStatus.PENDING) {
            throw BizException.conflict("复核任务已处理: " + task.getStatus());
        }
        DrugBag bag = bagRepository.findById(task.getBagId()).orElseThrow();
        RecycleBatch batch = batchRepository.findById(task.getBatchId()).orElseThrow();

        task.setStatus(approve ? ReviewStatus.APPROVED : ReviewStatus.REJECTED);
        task.setReviewerId(reviewerId);
        task.setDecisionNote(note);
        task.setDecidedAt(LocalDateTime.now());
        reviewTaskRepository.save(task);

        if (approve) {
            bag.setStatus(BagStatus.ACCEPTED);
            bagRepository.save(bag);
            batchService.grantBagPoints(batch, bag);
        } else {
            bag.setStatus(BagStatus.REVIEW_REJECTED);
            bagRepository.save(bag);
            if (bag.isPointsGranted()) {
                // 已发积分被复核否决 -> 扣回
                pointsService.revoke(batch.getResidentId(), bag.getPoints(), "BAG", bag.getBagNo(),
                        "复核拒绝扣回: " + note);
                bag.setPointsGranted(false);
                bag.setPointsState(BagPointsState.REVOKED);
                bagRepository.save(bag);
            }
            // 此前发放失败仍挂起的，随复核拒绝一并驳回，避免客服对否决袋补发
            grantFailureRepository.findByBagNoAndStatus(bag.getBagNo(), GrantFailureStatus.PENDING)
                    .ifPresent(failure -> {
                        failure.setStatus(GrantFailureStatus.REJECTED);
                        failure.setReviewNote("人工复核拒绝，异常积分不予发放: " + note);
                        failure.setHandledAt(LocalDateTime.now());
                        grantFailureRepository.save(failure);
                        bag.setPointsState(BagPointsState.REVOKED);
                        bagRepository.save(bag);
                    });
        }

        batchRefresher.refresh(batch.getId());
        return task;
    }
}
