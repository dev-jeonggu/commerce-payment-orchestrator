package com.paycore.saga.domain;

import com.paycore.payment.method.PaymentMethod;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * Saga 보상 트랜잭션 실패 Dead Letter 엔티티
 *
 * 보상 취소(cancelBySaga)가 실패하면 이 테이블에 기록하고
 * 재시도 스케줄러가 주기적으로 처리 시도.
 * 최대 재시도 초과 시 EXHAUSTED → 운영자 수동 처리 알람.
 */
@Entity
@Table(name = "saga_dead_letters",
    indexes = {
        @Index(name = "idx_dlq_status_created", columnList = "status, created_at")
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class SagaDeadLetter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_order_id", nullable = false)
    private String merchantOrderId;

    @Column(name = "tx_id")
    private String txId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method")
    private PaymentMethod paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SagaDeadLetterStatus status;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_attempted_at")
    private LocalDateTime lastAttemptedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private static final int MAX_ATTEMPTS = 5;

    @Builder
    public SagaDeadLetter(String merchantOrderId, String txId, PaymentMethod paymentMethod, String errorMessage) {
        this.merchantOrderId = merchantOrderId;
        this.txId = txId;
        this.paymentMethod = paymentMethod;
        this.errorMessage = errorMessage;
        this.status = SagaDeadLetterStatus.PENDING;
        this.attemptCount = 0;
    }

    public void markProcessing() {
        this.status = SagaDeadLetterStatus.PROCESSING;
        this.lastAttemptedAt = LocalDateTime.now();
    }

    public void markResolved() {
        this.status = SagaDeadLetterStatus.RESOLVED;
    }

    public void markFailed(String errorMsg) {
        this.attemptCount++;
        this.errorMessage = errorMsg;
        this.status = this.attemptCount >= MAX_ATTEMPTS
                ? SagaDeadLetterStatus.EXHAUSTED
                : SagaDeadLetterStatus.PENDING;
    }

    public boolean isExhausted() {
        return this.status == SagaDeadLetterStatus.EXHAUSTED;
    }
}
