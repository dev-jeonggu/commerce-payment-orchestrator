package com.paycore.payment.repository;

import com.paycore.payment.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByMerchantOrderId(String merchantOrderId);

    Optional<Payment> findByTxId(String txId);

    boolean existsByTxId(String txId);

    boolean existsByMerchantOrderId(String merchantOrderId);
}
