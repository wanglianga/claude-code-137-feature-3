package com.community.medrecycle.web;

import com.community.medrecycle.config.AuthContext;
import com.community.medrecycle.domain.Account;
import com.community.medrecycle.domain.PointsGrantFailure;
import com.community.medrecycle.domain.enums.GrantFailureStatus;
import com.community.medrecycle.domain.enums.Role;
import com.community.medrecycle.service.GrantFailureService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 积分发放失败复核：客服补发 / 改纸质兑换券 / 驳回；居民查看本人失败单状态 */
@RestController
@RequestMapping("/api/grant-failures")
@RequiredArgsConstructor
public class GrantFailureController {

    private final GrantFailureService grantFailureService;

    /** 客服按状态查看失败单；居民只能看本人的（后端按登录身份过滤） */
    @GetMapping
    public List<PointsGrantFailure> list(@RequestParam(required = false) GrantFailureStatus status) {
        Account current = AuthContext.require();
        if (current.getRole() == Role.RESIDENT) {
            return grantFailureService.list(null, current.getId());
        }
        AuthContext.requireRole(Role.CUSTOMER_SERVICE, Role.ADMIN);
        return grantFailureService.list(status, null);
    }

    /** 复核详情：含失败原因/规则快照/批次封袋/账户快照，以及补发后是否已兑换 */
    @GetMapping("/{failureNo}")
    public Map<String, Object> detail(@PathVariable String failureNo) {
        Account current = AuthContext.require();
        Map<String, Object> detail = grantFailureService.detail(failureNo);
        PointsGrantFailure failure = (PointsGrantFailure) detail.get("failure");
        if (current.getRole() == Role.RESIDENT
                && !failure.getResidentId().equals(current.getId())) {
            throw com.community.medrecycle.config.BizException.forbidden("只能查看本人的积分发放失败单");
        }
        if (current.getRole() != Role.RESIDENT) {
            AuthContext.requireRole(Role.CUSTOMER_SERVICE, Role.ADMIN);
        }
        return detail;
    }

    public record ReissueRequest(String note) {
    }

    /** 客服补发 */
    @PostMapping("/{failureNo}/reissue")
    public PointsGrantFailure reissue(@PathVariable String failureNo,
                                      @RequestBody(required = false) ReissueRequest request) {
        Account operator = AuthContext.requireRole(Role.CUSTOMER_SERVICE);
        return grantFailureService.reissue(operator.getId(), failureNo,
                request == null ? null : request.note());
    }

    public record PaperVoucherRequest(String voucherNo, String note) {
    }

    /** 客服改为纸质兑换券 */
    @PostMapping("/{failureNo}/paper-voucher")
    public PointsGrantFailure paperVoucher(@PathVariable String failureNo,
                                           @RequestBody(required = false) PaperVoucherRequest request) {
        Account operator = AuthContext.requireRole(Role.CUSTOMER_SERVICE);
        return grantFailureService.paperVoucher(operator.getId(), failureNo,
                request == null ? null : request.voucherNo(),
                request == null ? null : request.note());
    }

    public record RejectRequest(@NotBlank String note) {
    }

    /** 客服驳回异常积分 */
    @PostMapping("/{failureNo}/reject")
    public PointsGrantFailure reject(@PathVariable String failureNo,
                                     @Validated @RequestBody RejectRequest request) {
        Account operator = AuthContext.requireRole(Role.CUSTOMER_SERVICE);
        return grantFailureService.reject(operator.getId(), failureNo, request.note());
    }
}
