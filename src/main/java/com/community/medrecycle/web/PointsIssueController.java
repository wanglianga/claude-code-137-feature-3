package com.community.medrecycle.web;

import com.community.medrecycle.config.AuthContext;
import com.community.medrecycle.config.BizException;
import com.community.medrecycle.domain.Account;
import com.community.medrecycle.domain.PointsIssue;
import com.community.medrecycle.domain.enums.IssueStatus;
import com.community.medrecycle.domain.enums.Role;
import com.community.medrecycle.service.PointsIssueService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 积分发放失败补发：客服复核后补发积分 / 改纸质兑换券 / 驳回异常积分；居民可查本人来源与状态 */
@RestController
@RequestMapping("/api/points-issues")
@RequiredArgsConstructor
public class PointsIssueController {

    private final PointsIssueService issueService;

    /** 客服/管理员待办失败单（可按状态过滤） */
    @GetMapping
    public List<PointsIssue> list(@RequestParam(required = false) IssueStatus status) {
        AuthContext.requireRole(Role.SERVICE, Role.ADMIN);
        return issueService.list(status);
    }

    /** 居民查看本人的发放失败单及处理状态 */
    @GetMapping("/mine")
    public List<PointsIssue> mine(@RequestParam(required = false) IssueStatus status) {
        Account resident = AuthContext.requireRole(Role.RESIDENT);
        return issueService.listForResident(resident.getId(), status);
    }

    /** 详情：客服/管理员可见全部（附居民是否已兑换，防重复补发）；居民仅可查本人 */
    @GetMapping("/{issueNo}")
    public Map<String, Object> get(@PathVariable String issueNo) {
        Account operator = AuthContext.require();
        Map<String, Object> view = issueService.detail(issueNo);
        PointsIssue issue = (PointsIssue) view.get("issue");
        if (operator.getRole() == Role.RESIDENT) {
            if (!issue.getResidentId().equals(operator.getId())) {
                throw BizException.forbidden("只能查看本人的积分发放记录");
            }
        } else if (operator.getRole() != Role.SERVICE && operator.getRole() != Role.ADMIN) {
            throw BizException.forbidden("当前角色无权查看积分发放失败单");
        }
        return view;
    }

    public record ReviewRequest(String note) {
    }

    public record RejectRequest(@NotBlank(message = "驳回必须填写复核说明") String note) {
    }

    /** 客服复核后补发积分（幂等，失败单已处置则 409 拒绝，防止重复补发） */
    @PostMapping("/{issueNo}/reissue")
    public PointsIssue reissue(@PathVariable String issueNo,
                               @Validated @RequestBody(required = false) ReviewRequest request) {
        Account staff = AuthContext.requireRole(Role.SERVICE, Role.ADMIN);
        return issueService.reissue(staff.getId(), issueNo, request == null ? null : request.note());
    }

    /** 客服改为纸质兑换券（线下兑付，不入积分账户） */
    @PostMapping("/{issueNo}/voucher")
    public PointsIssue toVoucher(@PathVariable String issueNo,
                                 @Validated @RequestBody(required = false) ReviewRequest request) {
        Account staff = AuthContext.requireRole(Role.SERVICE, Role.ADMIN);
        return issueService.convertToVoucher(staff.getId(), issueNo, request == null ? null : request.note());
    }

    /** 客服驳回异常积分（经复核认定不应发放） */
    @PostMapping("/{issueNo}/reject")
    public PointsIssue reject(@PathVariable String issueNo,
                              @Validated @RequestBody RejectRequest request) {
        Account staff = AuthContext.requireRole(Role.SERVICE, Role.ADMIN);
        return issueService.reject(staff.getId(), issueNo, request.note());
    }
}
