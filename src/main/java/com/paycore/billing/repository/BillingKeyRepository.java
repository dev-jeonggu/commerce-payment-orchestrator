package com.paycore.billing.repository;

import com.paycore.billing.domain.BillingKey;
import com.paycore.payment.pg.PgProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BillingKeyRepository extends JpaRepository<BillingKey, Long> {

    List<BillingKey> findByUserIdAndDeletedFalseOrderByIsDefaultDescCreatedAtDesc(Long userId);

    Optional<BillingKey> findByIdAndUserIdAndDeletedFalse(Long id, Long userId);

    Optional<BillingKey> findByUserIdAndPgProviderAndIsDefaultTrueAndDeletedFalse(
            Long userId, PgProvider pgProvider);

    /** 해당 사용자의 PG별 기본 카드 해제 (새 기본 카드 등록 시) */
    List<BillingKey> findByUserIdAndPgProviderAndIsDefaultTrueAndDeletedFalse(
            Long userId, PgProvider pgProvider, org.springframework.data.domain.Pageable pageable);
}
