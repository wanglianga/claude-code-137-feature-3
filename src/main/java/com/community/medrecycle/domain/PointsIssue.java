package com.community.medrecycle.domain;

import com.community.medrecycle.domain.enums.AccountStatus;
import com.community.medrecycle.domain.enums.DrugCategory;
import com.community.medrecycle.domain.enums.GrantFailReason;
import com.community.medrecycle.domain.enums.IssueStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 积分发放失败单：居民完成称重后积分未能入账时生成，
 * 记录失败原因、积分规则快照、药品批次与居民账户当时状态，供客服复核后补发/转纸质券/驳回。
 * 一个封袋的一次发放失败对应一单（bagNo 唯一），天然防止重复补发。
 */
@Getter
@Setter
@Entity
@Table(name = "points_issue", uniqueConstraints = @UniqueConstraint(columnNames = "issueNo"),
        indexes = {
                @Index(name = "idx_issue_status", columnList = "status"),
                @Index(name = "idx_issue_resident", columnList = "residentId")
        })
public class PointsIssue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String issueNo;

    @Column(nullable = false)
    private Long residentId;

    private Long batchId;

    @Column(length = 32)
    private String batchNo;

    private Long bagId;

    @Column(length = 32)
    private String bagNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DrugCategory category;

    /** 应发积分（按规则快照与活动倍率计算） */
    @Column(nullable = false)
    private int expectedPoints;

    /** 预约时固化的积分规则快照，格式 TABLET:10,... */
    @Column(nullable = false, length = 512)
    private String pointsRuleSnapshot;

    /** 发放时本批次积分倍率（活动日） */
    @Column(nullable = false)
    private double pointsMultiplier = 1.0;

    @Column(nullable = false)
    private double weightKg;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private GrantFailReason failReason;

    /** 失败技术明细（异常信息等） */
    @Column(length = 512)
    private String failDetail;

    /** 失败时居民账户快照状态 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccountStatus accountStatusAtFailure = AccountStatus.ACTIVE;

    /** 失败时可用余额快照 */
    @Column(nullable = false)
    private int balanceAtFailure = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private IssueStatus status = IssueStatus.PENDING;

    /** 关联的异常记录单号（称重发放失败自动登记，处置办结后联动关闭） */
    @Column(length = 32)
    private String exceptionNo;

    /** 关联的环保拒收冻结单（若该批次之后被拒收且失败单仍在处理中） */
    private Long relatedFreezeId;

    /** 客服复核备注 */
    @Column(length = 512)
    private String reviewNote;

    private Long reviewedBy;

    @Column(length = 32)
    private String voucherNo;

    private LocalDateTime reviewedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
