package com.paycore.saga.repository;

import com.paycore.saga.domain.SagaDeadLetter;
import com.paycore.saga.domain.SagaDeadLetterStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SagaDeadLetterRepository extends JpaRepository<SagaDeadLetter, Long> {

    List<SagaDeadLetter> findByStatusOrderByCreatedAtAsc(SagaDeadLetterStatus status);

    boolean existsByOrderNoAndStatusIn(String orderNo, List<SagaDeadLetterStatus> statuses);
}
