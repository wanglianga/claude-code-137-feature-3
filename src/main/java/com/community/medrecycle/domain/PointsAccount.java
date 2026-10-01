package com.community.medrecycle.domain;

import com.community.medrecycle.domain.enums.AccountStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** 居民积分账户 */
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

    /** 总积分余额（含冻结积分） */
    @Column(nullable = false)
    private int balance = 0;

    /** 拒收待追缴的冻结积分：不可兑换、不可再次发放抵扣 */
    @Column(nullable = false)
    private int frozenPoints = 0;

    /** 追缴时余额不足形成的积分欠费，需在后续积分中冲抵 */
    @Column(nullable = false)
    private int debtPoints = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccountStatus status = AccountStatus.ACTIVE;

    @Version
    private long version;

    /** 可用于兑换的积分 = 总余额 - 冻结额 */
    public int availablePoints() {
        return balance - frozenPoints;
    }
}
