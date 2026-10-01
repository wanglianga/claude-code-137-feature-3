package com.community.medrecycle.domain.enums;

/** 居民积分账户状态：正常 / 冻结（冻结期间不能获得积分与兑换） */
public enum AccountStatus {
    ACTIVE,
    FROZEN,
    NOT_FOUND // 仅用于发放失败单快照：发放时账户尚不存在
}
