package com.paycore.payment.domain;

import com.paycore.payment.method.PaymentMethod;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "payments",
    indexes = {
        @Index(name = "idx_payment_merchant_order", columnList = "merchant_order_id")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_payment_merchant_order_id", columnNames = "merchant_order_id")
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 어느 가맹점의 결제인지 */
    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    /** 우리 내부 트랜잭션 ID */
    @Column(name = "tx_id", nullable = false, unique = true)
    private String txId;

    /** 가맹점 주문번호 */
    @Column(name = "merchant_order_id", nullable = false)
    private String merchantOrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false)
    private PaymentMethod paymentMethod;

    @Column(name = "paid_amount", nullable = false)
    private Long paidAmount;

    @Column(name = "cancelled_amount", nullable = false, columnDefinition = "bigint default 0")
    private Long cancelledAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Builder
    public Payment(String merchantId, String txId, String merchantOrderId,
                   PaymentMethod paymentMethod, Long paidAmount) {
        this.merchantId = merchantId;
        this.txId = txId;
        this.merchantOrderId = merchantOrderId;
        this.paymentMethod = paymentMethod;
        this.paidAmount = paidAmount;
        this.cancelledAmount = 0L;
        this.status = PaymentStatus.PAID;
    }

    public void cancel(Long cancelAmount) {
        if (this.status == PaymentStatus.CANCELLED) {
            throw new IllegalStateException("이미 전액 취소된 결제입니다.");
        }
        this.cancelledAmount = (this.cancelledAmount == null ? 0L : this.cancelledAmount) + cancelAmount;
        if (this.cancelledAmount.equals(this.paidAmount)) {
            this.status = PaymentStatus.CANCELLED;
        } else {
            this.status = PaymentStatus.PARTIAL_CANCELLED;
        }
        this.updatedAt = LocalDateTime.now();
    }
}
