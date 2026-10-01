package com.community.medrecycle.web;

import com.community.medrecycle.config.AuthContext;
import com.community.medrecycle.config.BizException;
import com.community.medrecycle.domain.Account;
import com.community.medrecycle.domain.PaperVoucher;
import com.community.medrecycle.domain.enums.Role;
import com.community.medrecycle.service.PointsIssueService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 纸质兑换券：居民查看本人券，社区点工作人员登记线下兑付 */
@RestController
@RequestMapping("/api/vouchers")
@RequiredArgsConstructor
public class VoucherController {

    private final PointsIssueService issueService;

    /** 居民查看本人纸质兑换券 */
    @GetMapping("/mine")
    public List<PaperVoucher> mine() {
        Account resident = AuthContext.requireRole(Role.RESIDENT);
        return issueService.vouchersForResident(resident.getId());
    }

    public record RedeemRequest(String note) {
    }

    /** 线下兑付登记（社区点管理员/回收员/客服），防重复兑付 */
    @PostMapping("/{voucherNo}/redeem")
    public PaperVoucher redeem(@PathVariable String voucherNo,
                               @RequestBody(required = false) RedeemRequest request) {
        Account operator = AuthContext.requireRole(Role.ADMIN, Role.RECYCLER, Role.SERVICE);
        return issueService.redeemVoucher(operator.getId(), voucherNo, request == null ? null : request.note());
    }

    /** 仅本人或工作人员可查券详情（简单封装，复用列表过滤） */
    @GetMapping("/{voucherNo}")
    public PaperVoucher get(@PathVariable String voucherNo) {
        Account operator = AuthContext.require();
        PaperVoucher voucher = issueService.getVoucher(voucherNo);
        if (operator.getRole() == Role.RESIDENT && !voucher.getResidentId().equals(operator.getId())) {
            throw BizException.forbidden("只能查看本人的兑换券");
        }
        return voucher;
    }
}
