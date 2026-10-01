package com.community.medrecycle.web;

import com.community.medrecycle.config.AuthContext;
import com.community.medrecycle.domain.Account;
import com.community.medrecycle.domain.enums.Role;
import com.community.medrecycle.service.PointsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 居民积分账户管理：管理员冻结/解冻账户（冻结后发放失败转客服，兑换暂停） */
@RestController
@RequestMapping("/api/admin/points-accounts")
@RequiredArgsConstructor
public class PointsAccountAdminController {

    private final PointsService pointsService;

    public record TargetRequest(Long residentId) {
    }

    @PostMapping("/freeze")
    public Map<String, Object> freeze(@RequestBody TargetRequest request) {
        AuthContext.requireRole(Role.ADMIN);
        pointsService.freezeAccount(request.residentId());
        return pointsService.accountView(request.residentId());
    }

    @PostMapping("/unfreeze")
    public Map<String, Object> unfreeze(@RequestBody TargetRequest request) {
        AuthContext.requireRole(Role.ADMIN);
        pointsService.unfreezeAccount(request.residentId());
        return pointsService.accountView(request.residentId());
    }

    @GetMapping
    public Map<String, Object> view(@RequestParam Long residentId) {
        Account operator = AuthContext.requireRole(Role.ADMIN, Role.SERVICE);
        return pointsService.accountView(residentId);
    }
}
