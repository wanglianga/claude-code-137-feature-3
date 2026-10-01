package com.community.medrecycle.domain.enums;

/** 拒收批次已发积分的冻结/追缴状态 */
public enum FreezeStatus {
    FROZEN,    // 已冻结（可用余额不足时含待追缴欠分），等待处理
    RECOVERED, // 已追缴核销
    RELEASED   // 已解除冻结并退回居民（如拒收复核撤销/申诉成立）
}
