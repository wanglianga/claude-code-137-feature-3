package com.community.medrecycle.domain;

import com.community.medrecycle.domain.enums.TxnType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 积分流水 */
@Getter
@Setter
@Entity
@Table(name = "points_transaction", indexes = {
        @Index(name = "idx_txn_account", columnList = "accountId"),
        @Index(name = "idx_txn_ref", columnList = "refType,refId")
})
public class PointsTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TxnType type;

    /** 有符号积分变动：发放为正，兑换/扣回为负 */
    @Column(nullable = false)
    private int amount;

    /** 变动后余额 */
    @Column(nullable = false)
    private int balanceAfter;

    /** 关联对象类型：BAG / EXCHANGE / EXCEPTION 等 */
    @Column(length = 32)
    private String refType;

    @Column(length = 64)
    private String refId;

    @Column(length = 256)
    private String remark;

    /** 冻结/解冻流水记录变动后的冻结积分，其余类型为空 */
    private Integer frozenBalanceAfter;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
