package com.community.medrecycle.service;

import com.community.medrecycle.config.BizException;
import com.community.medrecycle.config.NoGen;
import com.community.medrecycle.domain.*;
import com.community.medrecycle.domain.enums.AccountStatus;
import com.community.medrecycle.domain.enums.ExceptionType;
import com.community.medrecycle.domain.enums.ExchangeStatus;
import com.community.medrecycle.domain.enums.TxnType;
import com.community.medrecycle.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 积分账户：发放/扣回/调账/兑换，拒收冻结/追缴/欠费冲抵，兑换走悲观锁应对峰值并发。 */
@Service
@RequiredArgsConstructor
public class PointsService {

    private final PointsAccountRepository accountRepository;
    private final PointsTransactionRepository transactionRepository;
    private final GiftRepository giftRepository;
    private final PointsExchangeRepository exchangeRepository;
    private final ExceptionRecordRepository exceptionRepository;

    /** 尝试发放结果：失败时携带账户状态快照，供调用方落积分发放失败单 */
    public record GrantResult(boolean success, int balanceAfter, String failReason,
                              AccountStatus accountStatus, int accountFrozenPoints) {
        static GrantResult ok(int balance) {
            return new GrantResult(true, balance, null, AccountStatus.ACTIVE, 0);
        }
    }

    @Transactional
    public int grant(Long residentId, int points, String refType, String refId, String remark) {
        return apply(residentId, points, TxnType.GRANT, refType, refId, remark);
    }

    /** 补发（客服复核发放失败单）：与正常发放同通道，先冲抵欠费 */
    @Transactional
    public PointsTransaction reissue(Long residentId, int points, String refId, String remark) {
        PointsAccount account = lockOrCreate(residentId);
        if (account.getStatus() == AccountStatus.FROZEN) {
            throw BizException.conflict("居民积分账户处于冻结状态，不能补发，请先解除冻结");
        }
        int[] split = creditWithDebtOffset(account, points);
        accountRepository.save(account);
        return saveTxn(account, TxnType.REISSUE, split[1], "GRANT_FAILURE", refId,
                remark + (split[0] > 0 ? "（其中 " + split[0] + " 分用于冲抵历史欠费）" : ""), null);
    }

    @Transactional
    public int revoke(Long residentId, int points, String refType, String refId, String remark) {
        return apply(residentId, -points, TxnType.REVOKE, refType, refId, remark);
    }

    @Transactional
    public int adjust(Long residentId, int signedDelta, String refType, String refId, String remark) {
        return apply(residentId, signedDelta, TxnType.ADJUST, refType, refId, remark);
    }

    /**
     * 尝试发放积分，业务失败不抛异常：账户冻结/欠费限制等场景返回失败结果，
     * 由称重流程记录积分发放失败单，等待客服复核补发。
     */
    @Transactional
    public GrantResult tryGrant(Long residentId, int points, String refType, String refId, String remark) {
        PointsAccount account = lockOrCreate(residentId);
        if (account.getStatus() == AccountStatus.FROZEN) {
            return new GrantResult(false, account.getBalance(), "居民积分账户被冻结，积分暂不能入账",
                    AccountStatus.FROZEN, account.getFrozenPoints());
        }
        int[] split = creditWithDebtOffset(account, points);
        accountRepository.save(account);
        saveTxn(account, TxnType.GRANT, split[1], refType, refId,
                remark + (split[0] > 0 ? "（其中 " + split[0] + " 分已冲抵欠费）" : ""), null);
        return GrantResult.ok(account.getBalance());
    }

    /** 入账并优先冲抵欠费，返回 [冲抵欠费额, 实际入余额额] */
    private int[] creditWithDebtOffset(PointsAccount account, int points) {
        int offset = Math.min(points, account.getDebtPoints());
        account.setDebtPoints(account.getDebtPoints() - offset);
        int credited = points - offset;
        account.setBalance(account.getBalance() + credited);
        return new int[]{offset, credited};
    }

    /** 账户加减积分并记流水（悲观锁保证串行） */
    private int apply(Long residentId, int signedAmount, TxnType type, String refType, String refId, String remark) {
        PointsAccount account = lockOrCreate(residentId);
        if (account.getStatus() == AccountStatus.FROZEN && signedAmount > 0) {
            throw BizException.conflict("居民积分账户处于冻结状态，不能入账积分");
        }
        account.setBalance(account.getBalance() + signedAmount);
        accountRepository.save(account);
        saveTxn(account, type, signedAmount, refType, refId, remark, null);
        return account.getBalance();
    }

    /** 拒收冻结：把已发积分从可用额中冻结，总余额不变 */
    @Transactional
    public PointsTransaction freeze(Long residentId, int points, String refType, String refId, String remark) {
        PointsAccount account = accountRepository.lockByResidentId(residentId)
                .orElseThrow(() -> BizException.notFound("居民积分账户不存在"));
        if (account.availablePoints() < points) {
            throw BizException.conflict(String.format(
                    "可冻结积分不足：需冻结 %d 分，当前可用仅 %d 分（余额 %d、已冻结 %d）",
                    points, account.availablePoints(), account.getBalance(), account.getFrozenPoints()));
        }
        account.setFrozenPoints(account.getFrozenPoints() + points);
        accountRepository.save(account);
        return saveTxn(account, TxnType.FREEZE, 0, refType, refId, remark, account.getFrozenPoints());
    }

    /**
     * 拒收联动：已发积分进入冻结/追缴流程。可冻结的可用积分全额冻结；
     * 居民已兑换导致可用不足的部分直接登记欠费，由后续积分自动冲抵。
     * 返回 [实际冻结额, 直接转欠费额]。
     */
    @Transactional
    public int[] freezeRejectedPoints(Long residentId, int points, String refType, String refId, String remark) {
        PointsAccount account = accountRepository.lockByResidentId(residentId)
                .orElseThrow(() -> BizException.notFound("居民积分账户不存在"));
        int frozen = Math.min(points, Math.max(0, account.availablePoints()));
        int debt = points - frozen;
        if (frozen > 0) {
            account.setFrozenPoints(account.getFrozenPoints() + frozen);
        }
        if (debt > 0) {
            account.setDebtPoints(account.getDebtPoints() + debt);
        }
        accountRepository.save(account);
        String detail = remark + "（冻结 " + frozen + " 分"
                + (debt > 0 ? "；该部分已被兑换，" + debt + " 分登记为欠费追缴" : "") + "）";
        saveTxn(account, TxnType.FREEZE, 0, refType, refId, detail, account.getFrozenPoints());
        return new int[]{frozen, debt};
    }

    /** 申述成立/解除拒收：冻结积分恢复可用 */
    @Transactional
    public PointsTransaction unfreeze(Long residentId, int points, String refType, String refId, String remark) {
        PointsAccount account = accountRepository.lockByResidentId(residentId)
                .orElseThrow(() -> BizException.notFound("居民积分账户不存在"));
        if (account.getFrozenPoints() < points) {
            throw BizException.conflict(String.format(
                    "解冻积分超过冻结额：申请解冻 %d 分，当前冻结 %d 分", points, account.getFrozenPoints()));
        }
        account.setFrozenPoints(account.getFrozenPoints() - points);
        accountRepository.save(account);
        return saveTxn(account, TxnType.UNFREEZE, 0, refType, refId, remark, account.getFrozenPoints());
    }

    /** 拒收解除：冻结部分恢复可用，拒收时因已兑换登记的欠费一并豁免 */
    @Transactional
    public PointsTransaction unfreezeRejected(Long residentId, int frozenPart, int waivedDebtPart,
                                              String refType, String refId, String remark) {
        PointsAccount account = accountRepository.lockByResidentId(residentId)
                .orElseThrow(() -> BizException.notFound("居民积分账户不存在"));
        account.setFrozenPoints(Math.max(0, account.getFrozenPoints() - frozenPart));
        account.setDebtPoints(Math.max(0, account.getDebtPoints() - waivedDebtPart));
        accountRepository.save(account);
        return saveTxn(account, TxnType.UNFREEZE, 0, refType, refId,
                remark + (waivedDebtPart > 0 ? "（豁免欠费 " + waivedDebtPart + " 分）" : ""),
                account.getFrozenPoints());
    }

    /**
     * 追缴冻结积分：余额内部分直接扣减；不足部分形成欠费，由后续积分自动冲抵。
     * 返回实际扣减额（欠费额 = points - 实际扣减）。
     */
    @Transactional
    public int clawback(Long residentId, int points, String refType, String refId, String remark) {
        PointsAccount account = accountRepository.lockByResidentId(residentId)
                .orElseThrow(() -> BizException.notFound("居民积分账户不存在"));
        int deducted = Math.min(account.getBalance(), points);
        int debt = points - deducted;
        account.setBalance(account.getBalance() - deducted);
        account.setFrozenPoints(Math.max(0, account.getFrozenPoints() - points));
        account.setDebtPoints(account.getDebtPoints() + debt);
        accountRepository.save(account);
        saveTxn(account, TxnType.REVOKE, -deducted, refType, refId,
                remark + (debt > 0 ? String.format("；余额不足，%d 分转为欠费，后续积分自动冲抵", debt) : ""),
                account.getFrozenPoints());
        return deducted;
    }

    /** 管理员冻结/解冻账户（账户不存在时先建立再置状态） */
    @Transactional
    public PointsAccount updateAccountStatus(Long residentId, AccountStatus status, String reason) {
        PointsAccount account = lockOrCreate(residentId);
        account.setStatus(status);
        accountRepository.save(account);
        saveTxn(account, status == AccountStatus.FROZEN ? TxnType.FREEZE : TxnType.UNFREEZE, 0,
                "ACCOUNT", residentId + "", "账户状态变更为 " + status + "：" + reason,
                account.getFrozenPoints());
        return account;
    }

    private PointsTransaction saveTxn(PointsAccount account, TxnType type, int amount,
                                      String refType, String refId, String remark, Integer frozenAfter) {
        PointsTransaction txn = new PointsTransaction();
        txn.setAccountId(account.getId());
        txn.setType(type);
        txn.setAmount(amount);
        txn.setBalanceAfter(account.getBalance());
        txn.setRefType(refType);
        txn.setRefId(refId);
        txn.setRemark(remark);
        txn.setFrozenBalanceAfter(frozenAfter);
        return transactionRepository.save(txn);
    }

    private PointsAccount lockOrCreate(Long residentId) {
        return accountRepository.lockByResidentId(residentId).orElseGet(() -> {
            PointsAccount account = new PointsAccount();
            account.setResidentId(residentId);
            account.setBalance(0);
            return accountRepository.save(account);
        });
    }

    @Transactional(readOnly = true)
    public int balanceOf(Long residentId) {
        return accountRepository.findByResidentId(residentId).map(PointsAccount::getBalance).orElse(0);
    }

    @Transactional(readOnly = true)
    public PointsAccount accountOf(Long residentId) {
        return accountRepository.findByResidentId(residentId).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<PointsTransaction> transactionsOf(Long residentId) {
        return accountRepository.findByResidentId(residentId)
                .map(a -> transactionRepository.findByAccountIdOrderByCreatedAtDesc(a.getId()))
                .orElse(List.of());
    }

    /**
     * 积分兑换。业务失败（账户冻结/欠费/余额不足/库存不足）不抛异常，而是落一条 FAILED 兑换单，
     * 并自动登记"积分兑换失败"异常，便于客服跟踪；峰值并发由账户+礼品双悲观锁保证不超卖。
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
        exchange.setExchangeNo(NoGen.next("EXC"));
        exchange.setResidentId(residentId);
        exchange.setGiftId(lockedGift.getId());
        exchange.setGiftName(lockedGift.getName());
        exchange.setCostPoints(lockedGift.getCostPoints());

        String failReason = checkExchangeBlocked(account, lockedGift);
        if (failReason != null) {
            exchange.setStatus(ExchangeStatus.FAILED);
            exchange.setFailReason(failReason);
            PointsExchange failed = exchangeRepository.save(exchange);
            recordExchangeFailedException(residentId, failed);
            return failed;
        }

        lockedGift.setStock(lockedGift.getStock() - 1);
        giftRepository.save(lockedGift);

        account.setBalance(account.getBalance() - lockedGift.getCostPoints());
        accountRepository.save(account);
        saveTxn(account, TxnType.EXCHANGE, -lockedGift.getCostPoints(), "EXCHANGE", exchange.getExchangeNo(),
                "兑换礼品: " + lockedGift.getName(), null);

        exchange.setStatus(ExchangeStatus.SUCCESS);
        return exchangeRepository.save(exchange);
    }

    /** 返回拦截原因；null 表示可以兑换 */
    private String checkExchangeBlocked(PointsAccount account, Gift gift) {
        if (account.getStatus() == AccountStatus.FROZEN) {
            return "积分账户被冻结，暂不能兑换";
        }
        if (account.getDebtPoints() > 0) {
            return "账户存在 " + account.getDebtPoints() + " 积分欠费，补缴冲抵前不能兑换";
        }
        if (gift.getStock() <= 0) {
            return "礼品库存不足";
        }
        if (account.availablePoints() < gift.getCostPoints()) {
            return "可用积分不足，当前可用 " + account.availablePoints()
                    + "（总余额 " + account.getBalance() + "，冻结 " + account.getFrozenPoints() + "）";
        }
        return null;
    }

    /** 兑换失败自动登记异常（无批次/封袋上下文），客服可在异常列表中跟进 */
    private void recordExchangeFailedException(Long residentId, PointsExchange exchange) {
        ExceptionRecord record = new ExceptionRecord();
        record.setExceptionNo(NoGen.next("EX"));
        record.setResidentId(residentId);
        record.setType(ExceptionType.EXCHANGE_FAILED);
        record.setDescription(String.format("积分兑换失败：礼品 %s（%d 分），兑换单 %s，原因：%s",
                exchange.getGiftName(), exchange.getCostPoints(), exchange.getExchangeNo(),
                exchange.getFailReason()));
        exceptionRepository.save(record);
    }

    @Transactional(readOnly = true)
    public List<PointsExchange> exchangesOf(Long residentId) {
        return exchangeRepository.findByResidentIdOrderByCreatedAtDesc(residentId);
    }
}
