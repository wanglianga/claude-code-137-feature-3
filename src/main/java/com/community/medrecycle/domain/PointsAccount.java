package com.community.medrecycle.domain;

import com.community.medrecycle.domain.enums.AccountStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** 居民积分账户：balance 为可用余额，frozenBalance 为因拒收被冻结的积分 */
@Getter
@Setter
@Entity
@Table(name = "points_account", uniqueConstraints = @UniqueConstraint(columnNames = "residentId"))
public class PointsAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long residentId;

    /** 可用积分（兑换只扣可用余额） */
    @Column(nullable = false)
    private int balance = 0;

    /** 因环保公司拒收被冻结、暂不可用的积分 */
    @Column(nullable = false)
    private int frozenBalance = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccountStatus status = AccountStatus.ACTIVE;

    @Version
    private long version;
}
