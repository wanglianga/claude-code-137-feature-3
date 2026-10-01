package com.community.medrecycle.domain;

import com.community.medrecycle.domain.enums.AccountStatus;
import com.community.medrecycle.domain.enums.GrantFailureStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 积分发放失败单：居民完成称重后积分未到账时留存的完整证据链——
 * 失败原因、积分规则快照、药品批次/封袋、发放时居民账户状态，
 * 供客服复核后补发 / 改纸质兑换券 / 驳回。
 */
@Getter
@Setter
@Entity
@Table(name = "points_grant_failure", uniqueConstraints = @UniqueConstraint(columnNames = "failureNo"),
        indexes = {
                @Index(name = "idx_gf_status", columnList = "status"),
                @Index(name = "idx_gf_resident", columnList = "residentId"),
                @Index(name = "idx_gf_bag", columnList = "bagNo")
        })
public class PointsGrantFailure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String failureNo;

    @Column(nullable = false)
    private Long residentId;

    @Column(nullable = false)
    private Long batchId;

    @Column(nullable = false, length = 32)
    private String batchNo;

    @Column(nullable = false)
    private Long bagId;

    @Column(nullable = false, length = 32)
    private String bagNo;

    /** 药品分类 */
    @Column(nullable = false, length = 32)
    private String category;

    @Column(nullable = false)
    private double weightKg;

    /** 应发积分（按预约时规则快照 × 活动倍率计算） */
    @Column(nullable = false)
    private int points;

    /** 预约时固化的积分规则快照，如 TABLET:10,ANTIBIOTIC:15 */
    @Column(nullable = false, length = 512)
    private String pointsRuleSnapshot;

    /** 活动日积分倍率 */
    @Column(nullable = false)
    private double pointsMultiplier = 1.0;

    /** 发放失败原因 */
    @Column(nullable = false, length = 256)
    private String failReason;

    /** 失败时账户状态（账户不存在记为 NOT_FOUND） */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccountStatus accountStatus;

    /** 失败时账户余额 */
    @Column(nullable = false)
    private int accountBalance;

    /** 失败时账户冻结积分 */
    @Column(nullable = false)
    private int accountFrozenPoints;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private GrantFailureStatus status = GrantFailureStatus.PENDING;

    /** 复核客服 */
    private Long handlerId;

    /** 复核备注 */
    @Column(length = 512)
    private String reviewNote;

    /** 改纸质兑换券时生成的券号 */
    @Column(length = 64)
    private String voucherNo;

    /** 补发积分流水号（补发后用于查询是否已被兑换） */
    private Long reissueTxnId;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime handledAt;
}
