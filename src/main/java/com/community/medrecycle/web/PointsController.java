package com.community.medrecycle.web;

import com.community.medrecycle.config.AuthContext;
import com.community.medrecycle.domain.Account;
import com.community.medrecycle.domain.PointsAccount;
import com.community.medrecycle.domain.PointsExchange;
import com.community.medrecycle.domain.PointsTransaction;
import com.community.medrecycle.domain.enums.AccountStatus;
import com.community.medrecycle.domain.enums.ExchangeStatus;
import com.community.medrecycle.domain.enums.Role;
import com.community.medrecycle.service.PointsService;
import com.community.medrecycle.service.PointsSourceService;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/points")
@RequiredArgsConstructor
public class PointsController {

    private final PointsService pointsService;
    private final PointsSourceService pointsSourceService;

    @GetMapping("/balance")
    public Map<String, Object> balance() {
        Account resident = AuthContext.requireRole(Role.RESIDENT);
        PointsAccount account = pointsService.accountOf(resident.getId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("residentId", resident.getId());
        body.put("balance", pointsService.balanceOf(resident.getId()));
        if (account != null) {
            body.put("status", account.getStatus());
            body.put("frozenPoints", account.getFrozenPoints());
            body.put("availablePoints", account.availablePoints());
            body.put("debtPoints", account.getDebtPoints());
        }
        return body;
    }

    @GetMapping("/transactions")
    public List<PointsTransaction> transactions() {
        Account resident = AuthContext.requireRole(Role.RESIDENT);
        return pointsService.transactionsOf(resident.getId());
    }

    /** 居民积分来源：每袋药品的品类/重量/积分规则/处理状态，以及拒收告知 */
    @GetMapping("/sources")
    public Map<String, Object> sources() {
        Account resident = AuthContext.requireRole(Role.RESIDENT);
        return pointsSourceService.sourcesOf(resident.getId());
    }

    public record ExchangeRequest(@NotNull Long giftId) {
    }

    /** 积分兑换：业务失败（余额/冻结/欠费/库存不足）返回 409 与失败单，便于异常跟踪 */
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

    public record AccountStatusRequest(@NotNull AccountStatus status, String reason) {
    }

    /** 管理员/客服冻结或解冻居民积分账户（冻结期间称重积分将落发放失败单） */
    @PostMapping("/accounts/{residentId}/status")
    public PointsAccount updateAccountStatus(@PathVariable Long residentId,
                                             @Validated @RequestBody AccountStatusRequest request) {
        AccountStatus status = request.status();
        if (status == AccountStatus.NOT_FOUND) {
            throw com.community.medrecycle.config.BizException.badRequest("不能将账户置为 NOT_FOUND");
        }
        AuthContext.requireRole(Role.ADMIN, Role.CUSTOMER_SERVICE);
        return pointsService.updateAccountStatus(residentId, status,
                request.reason() == null ? "" : request.reason());
    }

    /** 管理员/客服查看居民积分账户状态 */
    @GetMapping("/accounts/{residentId}")
    public PointsAccount account(@PathVariable Long residentId) {
        AuthContext.requireRole(Role.ADMIN, Role.CUSTOMER_SERVICE, Role.RECYCLER, Role.REVIEWER);
        return pointsService.accountOf(residentId);
    }
}
