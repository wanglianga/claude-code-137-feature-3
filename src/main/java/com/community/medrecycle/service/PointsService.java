package com.community.medrecycle.service;

import com.community.medrecycle.config.BizException;
import com.community.medrecycle.domain.*;
import com.community.medrecycle.domain.enums.AccountStatus;
import com.community.medrecycle.domain.enums.ExchangeStatus;
import com.community.medrecycle.domain.enums.FreezeStatus;
import com.community.medrecycle.domain.enums.GrantFailReason;
import com.community.medrecycle.domain.enums.TxnType;
import com.community.medrecycle.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 积分账户：发放/补发/扣回/调账/兑换/拒收冻结与追缴。
 * balance 为可用余额（兑换只扣可用），frozenBalance 为拒收冻结；
 * 居民后续入账积分优先自动抵扣历史追缴欠分。兑换走悲观锁应对峰值并发。
 */
@Service
@RequiredArgsConstructor
public class PointsService {

    private final PointsAccountRepository accountRepository;
    private final PointsTransactionRepository transactionRepository;
    private final GiftRepository giftRepository;
    private final PointsExchangeRepository exchangeRepository;
    private final PointsFreezeRepository freezeRepository;

    /** 拒收冻结移动结果 */
    public record FreezeMovement(int frozenNow, int outstanding) {
    }

    @Transactional
    public int grant(Long residentId, int points, String refType, String refId, String remark) {
        return credit(residentId, points, TxnType.GRANT, refType, refId, remark);
    }

    /** 客服补发：允许入账（账户须正常），同样先抵扣追缴欠分 */
    @Transactional
    public int reissue(Long residentId, int points, String refType, String refId, String remark) {
        return credit(residentId, points, TxnType.REISSUE, refType, refId, remark);
    }

    @Transactional
    public int revoke(Long residentId, int points, String refType, String refId, String remark) {
        return applyAvailable(residentId, -points, TxnType.REVOKE, refType, refId, remark);
    }

    @Transactional
    public int adjust(Long residentId, int signedDelta, String refType, String refId, String remark) {
        return applyAvailable(residentId, signedDelta, TxnType.ADJUST, refType, refId, remark);
    }

    /** 入账积分：账户冻结则发放失败（落失败单转客服）；否则先自动抵扣待追缴欠分，净额进可用余额 */
    private int credit(Long residentId, int gross, TxnType type, String refType, String refId, String remark) {
        if (gross <= 0) {
            return balanceOf(residentId);
        }
        PointsAccount account = lockOrCreate(residentId);
        if (account.getStatus() == AccountStatus.FROZEN) {
            // 在任何写库失败之前抛出，外层称重事务不被污染，可安全落发放失败单
            throw new com.community.medrecycle.config.PointsGrantException(
                    GrantFailReason.ACCOUNT_FROZEN,
                    "居民积分账户处于冻结状态，应发 " + gross + " 分未入账");
        }

        int remaining = gross;
        // 最旧欠单优先抵扣
        List<PointsFreeze> debts = freezeRepository
                .findByResidentIdAndStatusAndOutstandingPointsGreaterThanOrderByCreatedAtAsc(
                        residentId, FreezeStatus.FROZEN, 0);
        for (PointsFreeze f : debts) {
            if (remaining <= 0) {
                break;
            }
            int take = Math.min(remaining, f.getOutstandingPoints());
            f.setOutstandingPoints(f.getOutstandingPoints() - take);
            f.setRecoveredPoints(f.getRecoveredPoints() + take);
            remaining -= take;
            // 入账即抵扣：可用余额不因该部分增加，留痕一条来源抵扣流水
            writeTxn(account.getId(), TxnType.RECOVER, -take, account.getBalance(),
                    "FREEZE", f.getFreezeNo(),
                    "入账积分优先抵扣拒收追缴欠分 " + take + "（来源 " + refType + ":" + refId + "）");
            if (f.getOutstandingPoints() == 0) {
                completeRecovery(account, f);
            }
            freezeRepository.save(f);
        }

        if (remaining > 0) {
            account.setBalance(account.getBalance() + remaining);
            accountRepository.save(account);
            writeTxn(account.getId(), type, remaining, account.getBalance(), refType, refId,
                    (gross == remaining ? remark : remark + "（已先抵扣追缴欠分 " + (gross - remaining) + "）"));
        } else {
            accountRepository.save(account);
        }
        return account.getBalance();
    }

    /** 欠分偿清：冻结积分全额追缴核销，从冻结余额核销（不再退回可用） */
    private void completeRecovery(PointsAccount account, PointsFreeze f) {
        int forfeited = f.getFrozenPoints();
        account.setFrozenBalance(Math.max(0, account.getFrozenBalance() - forfeited));
        f.setRecoveredPoints(f.getRecoveredPoints() + forfeited);
        f.setFrozenPoints(0);
        f.setStatus(FreezeStatus.RECOVERED);
        f.setHandledAt(LocalDateTime.now());
        if (f.getHandleNote() == null) {
            f.setHandleNote("欠分偿清，冻结积分自动追缴核销");
        }
        if (forfeited > 0) {
            writeTxn(account.getId(), TxnType.RECOVER, -forfeited, account.getBalance(),
                    "FREEZE", f.getFreezeNo(), "冻结积分追缴核销 " + forfeited
                            + "，该袋积分已全额追缴 " + f.getTotalPoints());
        }
    }

    /** 拒收冻结：尽可能多地把可用余额转入冻结，不足部分记为待追缴欠分 */
    @Transactional
    public FreezeMovement applyFreezeMovement(Long residentId, int points, String freezeNo, String reason) {
        if (points <= 0) {
            return new FreezeMovement(0, 0);
        }
        PointsAccount account = lockOrCreate(residentId);
        int frozenNow = Math.min(account.getBalance(), points);
        int outstanding = points - frozenNow;
        if (frozenNow > 0) {
            account.setBalance(account.getBalance() - frozenNow);
            account.setFrozenBalance(account.getFrozenBalance() + frozenNow);
            accountRepository.save(account);
            writeTxn(account.getId(), TxnType.FREEZE, -frozenNow, account.getBalance(),
                    "FREEZE", freezeNo, reason);
        }
        return new FreezeMovement(frozenNow, outstanding);
    }

    /** 解除冻结：冻结积分退回可用余额（须无欠分） */
    @Transactional
    public int unfreezeMovement(Long residentId, int frozenPoints, String freezeNo, String reason) {
        PointsAccount account = lockOrCreate(residentId);
        int back = Math.min(frozenPoints, account.getFrozenBalance());
        if (back > 0) {
            account.setFrozenBalance(account.getFrozenBalance() - back);
            account.setBalance(account.getBalance() + back);
            accountRepository.save(account);
            writeTxn(account.getId(), TxnType.UNFREEZE, back, account.getBalance(),
                    "FREEZE", freezeNo, reason);
        }
        return account.getBalance();
    }

    /** 追缴核销：冻结积分彻底核销（不退回可用余额） */
    @Transactional
    public void forfeitFrozen(Long residentId, int frozenPoints, String freezeNo, String reason) {
        PointsAccount account = lockOrCreate(residentId);
        int take = Math.min(frozenPoints, account.getFrozenBalance());
        if (take > 0) {
            account.setFrozenBalance(account.getFrozenBalance() - take);
            accountRepository.save(account);
            writeTxn(account.getId(), TxnType.RECOVER, -take, account.getBalance(),
                    "FREEZE", freezeNo, reason);
        }
    }

    /** 账户加减可用积分并记流水（扣回/调账），保持原始语义，不做非负拦截 */
    private int applyAvailable(Long residentId, int signedAmount, TxnType type, String refType, String refId, String remark) {
        PointsAccount account = lockOrCreate(residentId);
        account.setBalance(account.getBalance() + signedAmount);
        accountRepository.save(account);
        writeTxn(account.getId(), type, signedAmount, account.getBalance(), refType, refId, remark);
        return account.getBalance();
    }

    private void writeTxn(Long accountId, TxnType type, int amount, int balanceAfter,
                          String refType, String refId, String remark) {
        PointsTransaction txn = new PointsTransaction();
        txn.setAccountId(accountId);
        txn.setType(type);
        txn.setAmount(amount);
        txn.setBalanceAfter(balanceAfter);
        txn.setRefType(refType);
        txn.setRefId(refId);
        txn.setRemark(remark);
        transactionRepository.save(txn);
    }

    PointsAccount lockOrCreate(Long residentId) {
        return accountRepository.lockByResidentId(residentId).orElseGet(() -> {
            PointsAccount account = new PointsAccount();
            account.setResidentId(residentId);
            account.setBalance(0);
            account.setFrozenBalance(0);
            account.setStatus(AccountStatus.ACTIVE);
            return accountRepository.save(account);
        });
    }

    /** 账户状态与余额快照（发放失败时记录） */
    @Transactional(readOnly = true)
    public AccountSnapshot snapshot(Long residentId) {
        return accountRepository.findByResidentId(residentId)
                .map(a -> new AccountSnapshot(a.getStatus(), a.getBalance(), a.getFrozenBalance()))
                .orElse(new AccountSnapshot(null, 0, 0));
    }

    public record AccountSnapshot(AccountStatus status, int balance, int frozenBalance) {
    }

    @Transactional
    public void freezeAccount(Long residentId) {
        PointsAccount account = lockOrCreate(residentId);
        account.setStatus(AccountStatus.FROZEN);
        accountRepository.save(account);
    }

    @Transactional
    public void unfreezeAccount(Long residentId) {
        PointsAccount account = lockOrCreate(residentId);
        account.setStatus(AccountStatus.ACTIVE);
        accountRepository.save(account);
    }

    @Transactional(readOnly = true)
    public boolean isFrozen(Long residentId) {
        return accountRepository.findByResidentId(residentId)
                .map(a -> a.getStatus() == AccountStatus.FROZEN).orElse(false);
    }

    @Transactional(readOnly = true)
    public int balanceOf(Long residentId) {
        return accountRepository.findByResidentId(residentId).map(PointsAccount::getBalance).orElse(0);
    }

    /** 账户视图（无账户时返回零余额正常账户视图，不写库） */
    @Transactional(readOnly = true)
    public Map<String, Object> accountView(Long residentId) {
        PointsAccount a = accountRepository.findByResidentId(residentId).orElse(null);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("residentId", residentId);
        view.put("balance", a == null ? 0 : a.getBalance());
        view.put("frozenBalance", a == null ? 0 : a.getFrozenBalance());
        view.put("status", a == null ? AccountStatus.ACTIVE : a.getStatus());
        return view;
    }

    @Transactional(readOnly = true)
    public List<PointsTransaction> transactionsOf(Long residentId) {
        return accountRepository.findByResidentId(residentId)
                .map(a -> transactionRepository.findByAccountIdOrderByCreatedAtDesc(a.getId()))
                .orElse(List.of());
    }

    /**
     * 积分兑换。业务失败（账户冻结/余额不足/库存不足）不抛异常，而是落一条 FAILED 兑换单，
     * 便于追踪"积分兑换失败"场景；峰值并发由账户+礼品双悲观锁保证不超卖。
     */
    @Transactional
    public PointsExchange exchange(Long residentId, Long giftId) {
        // 直接悲观锁读取（先账户后礼品，固定顺序避免死锁）；
        // 不能先普通 findById 再锁，否则锁升级时的版本校验会在并发下误报冲突
        PointsAccount account = accountRepository.lockByResidentId(residentId)
                .orElseThrow(() -> BizException.badRequest("居民积分账户不存在"));
        Gift lockedGift = giftRepository.lockById(giftId)
                .orElseThrow(() -> BizException.notFound("礼品不存在: " + giftId));

        PointsExchange exchange = new PointsExchange();
        exchange.setExchangeNo(com.community.medrecycle.config.NoGen.next("EXC"));
        exchange.setResidentId(residentId);
        exchange.setGiftId(lockedGift.getId());
        exchange.setGiftName(lockedGift.getName());
        exchange.setCostPoints(lockedGift.getCostPoints());

        if (account.getStatus() == AccountStatus.FROZEN) {
            exchange.setStatus(ExchangeStatus.FAILED);
            exchange.setFailReason("积分账户已冻结，暂停兑换");
            return exchangeRepository.save(exchange);
        }
        if (lockedGift.getStock() <= 0) {
            exchange.setStatus(ExchangeStatus.FAILED);
            exchange.setFailReason("礼品库存不足");
            return exchangeRepository.save(exchange);
        }
        if (account.getBalance() < lockedGift.getCostPoints()) {
            exchange.setStatus(ExchangeStatus.FAILED);
            exchange.setFailReason("积分余额不足，当前可用余额 " + account.getBalance());
            return exchangeRepository.save(exchange);
        }

        lockedGift.setStock(lockedGift.getStock() - 1);
        giftRepository.save(lockedGift);

        account.setBalance(account.getBalance() - lockedGift.getCostPoints());
        accountRepository.save(account);
        writeTxn(account.getId(), TxnType.EXCHANGE, -lockedGift.getCostPoints(), account.getBalance(),
                "EXCHANGE", exchange.getExchangeNo(), "兑换礼品: " + lockedGift.getName());

        exchange.setStatus(ExchangeStatus.SUCCESS);
        return exchangeRepository.save(exchange);
    }

    @Transactional(readOnly = true)
    public List<PointsExchange> exchangesOf(Long residentId) {
        return exchangeRepository.findByResidentIdOrderByCreatedAtDesc(residentId);
    }
}
