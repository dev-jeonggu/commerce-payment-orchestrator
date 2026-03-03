package com.paycore.payment.repository;

import com.paycore.payment.domain.PaymentLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentLogRepository extends JpaRepository<PaymentLog, Long> {

    List<PaymentLog> findByOrderNoOrderByCreatedAtDesc(String orderNo);
}
