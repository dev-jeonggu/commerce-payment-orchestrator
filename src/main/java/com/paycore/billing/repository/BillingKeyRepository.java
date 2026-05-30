package com.paycore.billing.repository;

import com.paycore.billing.domain.BillingKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BillingKeyRepository extends JpaRepository<BillingKey, Long> {

    List<BillingKey> findByUserIdAndDeletedFalseOrderByIsDefaultDescCreatedAtDesc(Long userId);

    Optional<BillingKey> findByIdAndUserIdAndDeletedFalse(Long id, Long userId);

    Optional<BillingKey> findByMerchantIdAndUserIdAndIsDefaultTrueAndDeletedFalse(
            String merchantId, Long userId);
}
