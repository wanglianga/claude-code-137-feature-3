package com.community.medrecycle.domain.enums;

/** 单袋积分的处理状态（居民积分来源视图直接展示） */
public enum BagPointsState {
    NOT_GRANTED,   // 未发放（含发放失败待补发）
    GRANTED,       // 已发放，正常可用
    FROZEN,        // 批次被环保公司拒收，积分已冻结待追缴/解冻
    REVOKED,       // 已追缴/扣回/驳回
    PAPER_VOUCHER  // 已改为纸质兑换券
}
