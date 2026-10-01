package com.community.medrecycle.domain.enums;

/** 纸质兑换券状态 */
public enum VoucherStatus {
    ISSUED,   // 已签发，待居民线下领取/兑付
    REDEEMED, // 已线下兑付
    VOID      // 已作废
}
