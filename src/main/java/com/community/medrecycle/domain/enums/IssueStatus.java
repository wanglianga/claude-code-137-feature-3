package com.community.medrecycle.domain.enums;

/** 积分发放失败单状态：待客服复核 -> 已补发 / 已转纸质券 / 已驳回 */
public enum IssueStatus {
    PENDING,     // 待复核处置
    REISSUED,    // 已补发积分
    VOUCHERED,   // 已改为纸质兑换券
    REJECTED     // 异常积分驳回，不予发放
}
