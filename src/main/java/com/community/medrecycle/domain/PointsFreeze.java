package com.community.medrecycle.domain;

import com.community.medrecycle.domain.enums.DrugCategory;
import com.community.medrecycle.domain.enums.FreezeSource;
import com.community.medrecycle.domain.enums.FreezeStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 拒收冻结/追缴单：环保公司拒收某封袋后，该袋已发给居民的积分进入冻结流程。
 * 可用余额足够则当场冻结（frozen 增加、available 减少）；不足部分记为待追缴欠分，
 * 居民后续入账积分自动抵扣，直至全额追缴核销。社区管理员登记拒收品类与处理依据向居民说明。
 */
@Getter
@Setter
@Entity
@Table(name = "points_freeze", uniqueConstraints = @UniqueConstraint(columnNames = "freezeNo"),
        indexes = {
                @Index(name = "idx_freeze_resident", columnList = "residentId"),
                @Index(name = "idx_freeze_status", columnList = "status")
        })
public class PointsFreeze {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String freezeNo;

    @Column(nullable = false)
    private Long residentId;

    @Column(nullable = false)
    private Long batchId;

    @Column(length = 32)
    private String batchNo;

    @Column(nullable = false)
    private Long bagId;

    @Column(length = 32)
    private String bagNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DrugCategory category;

    /** 应冻结/追缴的积分总额（该袋已发积分） */
    @Column(nullable = false)
    private int totalPoints;

    /** 已从可用余额冻结的积分 */
    @Column(nullable = false)
    private int frozenPoints = 0;

    /** 已追缴核销积分（含入账抵扣） */
    @Column(nullable = false)
    private int recoveredPoints = 0;

    /** 尚欠积分（可用余额不足、待后续入账抵扣） */
    @Column(nullable = false)
    private int outstandingPoints = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FreezeSource source = FreezeSource.INITIAL_GRANT;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FreezeStatus status = FreezeStatus.FROZEN;

    /** 关联环保拒收异常单 */
    @Column(length = 32)
    private String exceptionNo;

    /** 拒收品类（向居民说明） */
    @Column(nullable = false, length = 64)
    private String rejectedCategory;

    /** 环保公司拒收原因 */
    @Column(length = 256)
    private String rejectReason;

    /** 处理依据（社区管理员登记，向居民说明的制度/文件依据） */
    @Column(length = 512)
    private String handlingBasis;

    private Long basisRecordedBy;

    private LocalDateTime basisRecordedAt;

    /** 追缴/解冻经办人 */
    private Long handledBy;

    @Column(length = 256)
    private String handleNote;

    private LocalDateTime handledAt;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
