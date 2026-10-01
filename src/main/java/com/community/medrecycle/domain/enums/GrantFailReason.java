package com.community.medrecycle.domain.enums;

/** 居民完成称重后积分发放失败的原因分类 */
public enum GrantFailReason {
    GRANT_SYSTEM_ERROR, // 积分账务系统故障/超时（发放未入账）
    ACCOUNT_FROZEN,     // 居民积分账户被冻结，暂不能入账
    ACCOUNT_NOT_FOUND,  // 居民积分账户不存在且未能即时开立
    RULE_MISSING,       // 缺少品类积分规则，无法计价
    OTHER               // 其他原因
}
