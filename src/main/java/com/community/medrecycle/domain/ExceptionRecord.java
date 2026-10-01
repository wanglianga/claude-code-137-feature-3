package com.community.medrecycle.domain;

import com.community.medrecycle.domain.enums.ExceptionStatus;
import com.community.medrecycle.domain.enums.ExceptionType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 异常记录：与批次/袋关联，贯穿预约到交接全链路 */
@Getter
@Setter
@Entity
@Table(name = "exception_record", uniqueConstraints = @UniqueConstraint(columnNames = "exceptionNo"),
        indexes = @Index(name = "idx_exception_status", columnList = "status"))
public class ExceptionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String exceptionNo;

    private Long batchId;

    @Column(length = 32)
    private String batchNo;

    private Long bagId;

    @Column(length = 32)
    private String bagNo;

    /** 关联居民（公司拒收积分处置、兑换失败时记录，便于居民侧查询与告知） */
    private Long residentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ExceptionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ExceptionStatus status = ExceptionStatus.OPEN;

    @Column(nullable = false, length = 512)
    private String description;

    private Long handlerId;

    @Column(length = 512)
    private String resolution;

    /** 拒收处置结论：FREEZE 冻结待追缴 / CLAWBACK 已追缴 / UNFREEZE 申述解冻 */
    @Column(length = 16)
    private String resolutionAction;

    /** 环保公司拒收品类（如 EXTERNAL 外用药） */
    @Column(length = 32)
    private String rejectCategory;

    /** 处理依据（制度条款/拒收名录条目），用于向居民说明 */
    @Column(length = 512)
    private String regulationBasis;

    /** 给居民的告知说明（拒收品类与处理依据） */
    @Column(length = 512)
    private String residentNotice;

    /** 本次处置涉及的积分（冻结/追缴/解冻额度） */
    private Integer affectedPoints;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime resolvedAt;
}
