package com.paycore.saga.repository;

import com.paycore.saga.domain.SagaDeadLetter;
import com.paycore.saga.domain.SagaDeadLetterStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SagaDeadLetterRepository extends JpaRepository<SagaDeadLetter, Long> {

    List<SagaDeadLetter> findByStatusOrderByCreatedAtAsc(SagaDeadLetterStatus status);

    Page<SagaDeadLetter> findByStatus(SagaDeadLetterStatus status, Pageable pageable);

    // existsByOrderNoAndStatusIn → orderNo 필드는 merchantOrderId로 변경됨
    boolean existsByMerchantOrderIdAndStatusIn(String merchantOrderId, List<SagaDeadLetterStatus> statuses);
}
