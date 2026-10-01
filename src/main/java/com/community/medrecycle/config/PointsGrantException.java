package com.community.medrecycle.config;

import com.community.medrecycle.domain.enums.GrantFailReason;
import lombok.Getter;

/** 积分发放被业务条件阻断（如账户冻结），供发放编排层捕获并落失败单 */
@Getter
public class PointsGrantException extends RuntimeException {

    private final GrantFailReason reason;

    public PointsGrantException(GrantFailReason reason, String message) {
        super(message);
        this.reason = reason;
    }
}
