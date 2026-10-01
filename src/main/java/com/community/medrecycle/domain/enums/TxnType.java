package com.community.medrecycle.domain.enums;

public enum TxnType {
    GRANT,    // 发放
    EXCHANGE, // 兑换扣减
    REVOKE,   // 扣回（异常/追缴）
    ADJUST,   // 调账（称重争议等）
    REISSUE,  // 客服补发（此前发放失败）
    FREEZE,   // 拒收冻结（仅冻结额度变动，不动总余额）
    UNFREEZE  // 冻结解除
}
