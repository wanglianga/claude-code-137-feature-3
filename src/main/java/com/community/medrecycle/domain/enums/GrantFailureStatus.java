package com.community.medrecycle.domain.enums;

/** 积分发放失败单的客服复核结论 */
public enum GrantFailureStatus {
    PENDING,        // 待客服复核
    REISSUED,       // 已补发到账
    PAPER_VOUCHER,  // 已改为纸质兑换券
    REJECTED        // 已驳回（异常积分不予发放）
}
