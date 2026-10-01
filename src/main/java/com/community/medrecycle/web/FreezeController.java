package com.community.medrecycle.web;

import com.community.medrecycle.config.AuthContext;
import com.community.medrecycle.config.BizException;
import com.community.medrecycle.domain.Account;
import com.community.medrecycle.domain.PointsFreeze;
import com.community.medrecycle.domain.enums.FreezeStatus;
import com.community.medrecycle.domain.enums.Role;
import com.community.medrecycle.service.FreezeService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 拒收积分冻结/追缴：管理员登记拒收品类与处理依据、追缴/解冻；居民查看本人冻结与说明 */
@RestController
@RequestMapping("/api/points-freezes")
@RequiredArgsConstructor
public class FreezeController {

    private final FreezeService freezeService;

    /** 管理员/客服查看冻结追缴单（可按状态过滤） */
    @GetMapping
    public List<PointsFreeze> list(@RequestParam(required = false) FreezeStatus status) {
        AuthContext.requireRole(Role.ADMIN, Role.SERVICE);
        return freezeService.list(status);
    }

    /** 居民查看本人被拒收冻结/追缴的积分与处理依据 */
    @GetMapping("/mine")
    public List<PointsFreeze> mine() {
        Account resident = AuthContext.requireRole(Role.RESIDENT);
        return freezeService.listForResident(resident.getId());
    }

    /** 详情：居民仅本人，管理员/客服可见全部 */
    @GetMapping("/{freezeNo}")
    public Map<String, Object> get(@PathVariable String freezeNo) {
        Account operator = AuthContext.require();
        Map<String, Object> view = freezeService.detail(freezeNo);
        PointsFreeze freeze = (PointsFreeze) view.get("freeze");
        if (operator.getRole() == Role.RESIDENT) {
            if (!freeze.getResidentId().equals(operator.getId())) {
                throw BizException.forbidden("只能查看本人的冻结记录");
            }
        } else if (operator.getRole() != Role.ADMIN && operator.getRole() != Role.SERVICE) {
            throw BizException.forbidden("当前角色无权查看冻结记录");
        }
        return view;
    }

    public record BasisRequest(String rejectedCategory,
                               @NotBlank(message = "处理依据不能为空") String handlingBasis) {
    }

    /** 社区管理员登记拒收品类与处理依据，用于向居民说明 */
    @PostMapping("/{freezeNo}/basis")
    public PointsFreeze recordBasis(@PathVariable String freezeNo,
                                    @Validated @RequestBody BasisRequest request) {
        Account admin = AuthContext.requireRole(Role.ADMIN);
        return freezeService.recordBasis(admin.getId(), freezeNo,
                request.rejectedCategory(), request.handlingBasis());
    }

    public record HandleRequest(String note) {
    }

    /** 主动追缴：用当前可用余额偿还欠分，偿清则冻结积分一并核销 */
    @PostMapping("/{freezeNo}/recover")
    public PointsFreeze recover(@PathVariable String freezeNo,
                                @RequestBody(required = false) HandleRequest request) {
        Account admin = AuthContext.requireRole(Role.ADMIN, Role.SERVICE);
        return freezeService.recover(admin.getId(), freezeNo, request == null ? null : request.note());
    }

    /** 申诉成立/拒收复核撤销：解除冻结退回积分（须无追缴欠分） */
    @PostMapping("/{freezeNo}/release")
    public PointsFreeze release(@PathVariable String freezeNo,
                                @RequestBody(required = false) HandleRequest request) {
        Account admin = AuthContext.requireRole(Role.ADMIN);
        return freezeService.release(admin.getId(), freezeNo, request == null ? null : request.note());
    }
}
