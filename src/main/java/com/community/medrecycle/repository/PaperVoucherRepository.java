package com.community.medrecycle.repository;

import com.community.medrecycle.domain.PaperVoucher;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaperVoucherRepository extends JpaRepository<PaperVoucher, Long> {
    Optional<PaperVoucher> findByVoucherNo(String voucherNo);

    List<PaperVoucher> findByResidentIdOrderByIssuedAtDesc(Long residentId);

    Optional<PaperVoucher> findByIssueNo(String issueNo);
}
