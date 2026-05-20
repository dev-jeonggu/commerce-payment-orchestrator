package com.paycore.payment.domain;

import com.paycore.payment.pg.PgProvider;
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
        @Index(name = "idx_payment_merchant_uid", columnList = "merchant_uid")
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    /**
     * PG사 고유 결제번호 (iamport imp_uid)
     */
    @Column(name = "imp_uid", nullable = false, unique = true)
    private String impUid;

    /**
     * 가맹점 주문번호 (= orderNo)
     */
    @Column(name = "merchant_uid", nullable = false)
    private String merchantUid;

    @Column(name = "pay_method")
    private String payMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "pg_provider")
    private PgProvider pgProvider;

    @Column(name = "paid_amount", nullable = false)
    private Long paidAmount;

    @Column(name = "cancelled_amount")
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
    public Payment(Long orderId, String impUid, String merchantUid,
                   String payMethod, Long paidAmount, PgProvider pgProvider) {
        this.orderId = orderId;
        this.impUid = impUid;
        this.merchantUid = merchantUid;
        this.payMethod = payMethod;
        this.paidAmount = paidAmount;
        this.pgProvider = pgProvider != null ? pgProvider : PgProvider.PORTONE;
        this.cancelledAmount = 0L;
        this.status = PaymentStatus.PAID;
    }

    public void cancel(Long cancelAmount) {
        this.cancelledAmount = (this.cancelledAmount == null ? 0L : this.cancelledAmount) + cancelAmount;
        if (this.cancelledAmount.equals(this.paidAmount)) {
            this.status = PaymentStatus.CANCELLED;
        } else {
            this.status = PaymentStatus.PARTIAL_CANCELLED;
        }
        this.updatedAt = LocalDateTime.now();
    }
}
