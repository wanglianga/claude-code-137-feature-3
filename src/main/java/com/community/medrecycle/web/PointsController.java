package com.community.medrecycle.web;

import com.community.medrecycle.config.AuthContext;
import com.community.medrecycle.domain.Account;
import com.community.medrecycle.domain.PointsExchange;
import com.community.medrecycle.domain.PointsTransaction;
import com.community.medrecycle.domain.enums.ExchangeStatus;
import com.community.medrecycle.domain.enums.Role;
import com.community.medrecycle.service.PointsService;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/points")
@RequiredArgsConstructor
public class PointsController {

    private final PointsService pointsService;
    private final com.community.medrecycle.service.PointsIssueService pointsIssueService;

    @GetMapping("/balance")
    public Map<String, Object> balance() {
        Account resident = AuthContext.requireRole(Role.RESIDENT);
        return Map.of("residentId", resident.getId(), "balance", pointsService.balanceOf(resident.getId()));
    }

    /** 账户视图：可用余额、拒收冻结余额、账户状态 */
    @GetMapping("/account")
    public Map<String, Object> account() {
        Account resident = AuthContext.requireRole(Role.RESIDENT);
        return pointsService.accountView(resident.getId());
    }

    /**
     * 居民积分台账：每笔积分来源流水 + 发放失败单处理状态 + 纸质兑换券 +
     * 拒收冻结/追缴与处理依据，一屏看清积分从哪来、现在什么状态。
     */
    @GetMapping("/ledger")
    public Map<String, Object> ledger() {
        Account resident = AuthContext.requireRole(Role.RESIDENT);
        return pointsIssueService.residentPointsView(resident.getId());
    }

    @GetMapping("/transactions")
    public List<PointsTransaction> transactions() {
        Account resident = AuthContext.requireRole(Role.RESIDENT);
        return pointsService.transactionsOf(resident.getId());
    }

    public record ExchangeRequest(@NotNull Long giftId) {
    }

    /** 积分兑换：失败（余额/库存不足）返回 409 与失败单，便于异常跟踪 */
    @PostMapping("/exchange")
    public ResponseEntity<PointsExchange> exchange(@Validated @RequestBody ExchangeRequest request) {
        Account resident = AuthContext.requireRole(Role.RESIDENT);
        PointsExchange exchange = pointsService.exchange(resident.getId(), request.giftId());
        if (exchange.getStatus() == ExchangeStatus.SUCCESS) {
            return ResponseEntity.ok(exchange);
        }
        return ResponseEntity.status(409).body(exchange);
    }

    @GetMapping("/exchanges")
    public List<PointsExchange> exchanges() {
        Account resident = AuthContext.requireRole(Role.RESIDENT);
        return pointsService.exchangesOf(resident.getId());
    }
}
