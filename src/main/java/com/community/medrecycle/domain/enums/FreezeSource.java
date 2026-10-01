package com.community.medrecycle.domain.enums;

/** 被冻结/追缴积分的来源 */
public enum FreezeSource {
    INITIAL_GRANT, // 称重/复核后的原始发放
    REISSUE        // 客服补发入账
}
