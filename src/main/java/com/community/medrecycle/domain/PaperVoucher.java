package com.community.medrecycle.domain;

import com.community.medrecycle.domain.enums.DrugCategory;
import com.community.medrecycle.domain.enums.VoucherStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 纸质兑换券：客服把异常积分改为线下兑付时签发，居民凭券到社区点兑换等值物品 */
@Getter
@Setter
@Entity
@Table(name = "paper_voucher", uniqueConstraints = @UniqueConstraint(columnNames = "voucherNo"),
        indexes = @Index(name = "idx_voucher_resident", columnList = "residentId"))
public class PaperVoucher {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String voucherNo;

    @Column(nullable = false)
    private Long residentId;

    /** 券面积分（等值） */
    @Column(nullable = false)
    private int pointsValue;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DrugCategory category;

    /** 来源失败单 */
    @Column(nullable = false, length = 32)
    private String issueNo;

    private Long batchId;

    @Column(length = 32)
    private String batchNo;

    @Column(length = 32)
    private String bagNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private VoucherStatus status = VoucherStatus.ISSUED;

    /** 签发客服 */
    private Long issuedBy;

    @Column(length = 256)
    private String issueNote;

    @Column(nullable = false)
    private LocalDateTime issuedAt = LocalDateTime.now();

    /** 线下兑付登记 */
    private Long redeemedBy;

    private LocalDateTime redeemedAt;

    @Column(length = 256)
    private String redeemNote;
}
