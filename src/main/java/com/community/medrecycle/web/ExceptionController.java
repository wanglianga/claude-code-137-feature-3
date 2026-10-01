package com.community.medrecycle.web;

import com.community.medrecycle.config.AuthContext;
import com.community.medrecycle.domain.Account;
import com.community.medrecycle.domain.ExceptionRecord;
import com.community.medrecycle.domain.enums.ExceptionStatus;
import com.community.medrecycle.domain.enums.ExceptionType;
import com.community.medrecycle.domain.enums.Role;
import com.community.medrecycle.service.ExceptionService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/exceptions")
@RequiredArgsConstructor
public class ExceptionController {

    private final ExceptionService exceptionService;

    public record RaiseRequest(String batchNo, String bagNo,
                               @NotNull ExceptionType type, @NotBlank String description) {
    }

    /** 上报异常（回收员/管理员/复核员；居民也可上报自己批次的问题） */
    @PostMapping
    public ExceptionRecord raise(@Validated @RequestBody RaiseRequest request) {
        AuthContext.require();
        return exceptionService.raise(request.batchNo(), request.bagNo(), request.type(), request.description());
    }

    @GetMapping
    public List<ExceptionRecord> list(@RequestParam(required = false) ExceptionStatus status) {
        AuthContext.requireRole(Role.RECYCLER, Role.REVIEWER, Role.CUSTOMER_SERVICE, Role.ADMIN);
        return exceptionService.list(status);
    }

    /** 居民查看本人相关异常（拒收品类、处理依据、告知与处置结论） */
    @GetMapping("/mine")
    public List<ExceptionRecord> mine() {
        Account resident = AuthContext.requireRole(Role.RESIDENT);
        return exceptionService.listByResident(resident.getId());
    }

    @GetMapping("/{exceptionNo}")
    public ExceptionRecord get(@PathVariable String exceptionNo) {
        AuthContext.require();
        return exceptionService.get(exceptionNo);
    }

    @PostMapping("/{exceptionNo}/process")
    public ExceptionRecord process(@PathVariable String exceptionNo) {
        Account operator = AuthContext.requireRole(Role.RECYCLER, Role.REVIEWER,
                Role.CUSTOMER_SERVICE, Role.ADMIN);
        return exceptionService.process(operator.getId(), exceptionNo);
    }

    public record ResolveRequest(@NotBlank String resolution) {
    }

    @PostMapping("/{exceptionNo}/resolve")
    public ExceptionRecord resolve(@PathVariable String exceptionNo,
                                   @Validated @RequestBody ResolveRequest request) {
        Account operator = AuthContext.requireRole(Role.RECYCLER, Role.REVIEWER,
                Role.CUSTOMER_SERVICE, Role.ADMIN);
        return exceptionService.resolve(operator.getId(), exceptionNo, request.resolution());
    }

    public record ReweighRequest(@Positive double newWeightKg) {
    }

    /** 称重争议复秤 */
    @PostMapping("/{exceptionNo}/reweigh")
    public ExceptionRecord reweigh(@PathVariable String exceptionNo,
                                   @Validated @RequestBody ReweighRequest request) {
        Account operator = AuthContext.requireRole(Role.RECYCLER, Role.ADMIN);
        return exceptionService.reweigh(operator.getId(), exceptionNo, request.newWeightKg());
    }

    public record RejectHandleRequest(@NotBlank String action,          // CLAWBACK 追缴 / UNFREEZE 解冻
                                      @NotBlank String regulationBasis, // 处理依据
                                      String residentNotice) {          // 给居民的告知（可空，后端生成默认告知）
    }

    /** 社区管理员处置拒收积分：追缴（冻结积分扣回）或解冻（恢复可用），必须登记处理依据 */
    @PostMapping("/{exceptionNo}/reject-handling")
    public ExceptionRecord rejectHandling(@PathVariable String exceptionNo,
                                          @Validated @RequestBody RejectHandleRequest request) {
        Account admin = AuthContext.requireRole(Role.ADMIN);
        return exceptionService.handleReject(admin.getId(), exceptionNo, request.action(),
                request.regulationBasis(), request.residentNotice());
    }
}
