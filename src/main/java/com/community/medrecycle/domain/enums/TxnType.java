package com.community.medrecycle.domain.enums;

public enum TxnType {
    GRANT,    // 发放（称重/复核）
    REISSUE,  // 客服补发
    EXCHANGE, // 兑换扣减
    REVOKE,   // 扣回（异常）
    ADJUST,   // 调账（称重争议等）
    FREEZE,   // 拒收冻结（可用 -> 冻结）
    UNFREEZE, // 解除冻结（冻结 -> 可用）
    RECOVER   // 追缴核销（含入账抵扣历史欠分）
}
