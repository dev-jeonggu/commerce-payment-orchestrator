package com.paycore.order.domain;

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
    name = "orders",
    indexes = {
        @Index(name = "idx_order_status_created", columnList = "status, created_at")
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false, unique = true)
    private String orderNo;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(name = "total_amount", nullable = false)
    private Long totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    /**
     * 결제에 사용된 PG사
     *
     * [하위 호환] 기존 데이터는 NULL일 수 있음 → PgRouter에서 NULL 시 PORTONE으로 fallback
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "pg_provider")
    private PgProvider pgProvider;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Builder
    public Order(String orderNo, Long userId, Long itemId, Long totalAmount, PgProvider pgProvider) {
        this.orderNo = orderNo;
        this.userId = userId;
        this.itemId = itemId;
        this.totalAmount = totalAmount;
        this.pgProvider = pgProvider != null ? pgProvider : PgProvider.PORTONE;
        this.status = OrderStatus.PENDING;
    }

    public void markAsPaid() {
        validateStatusTransition(OrderStatus.PAID);
        this.status = OrderStatus.PAID;
        this.updatedAt = LocalDateTime.now();
    }

    /** 가상계좌 발급 완료 → 입금 대기 상태 */
    public void markAsPendingPayment() {
        if (this.status != OrderStatus.PENDING) {
            throw new IllegalStateException("PENDING 상태의 주문만 PENDING_PAYMENT로 전환할 수 있습니다. 현재: " + this.status);
        }
        this.status = OrderStatus.PENDING_PAYMENT;
        this.updatedAt = LocalDateTime.now();
    }

    public void markAsCancelled() {
        if (this.status == OrderStatus.CANCELLED) {
            throw new IllegalStateException("이미 취소된 주문입니다.");
        }
        this.status = OrderStatus.CANCELLED;
        this.updatedAt = LocalDateTime.now();
    }

    public void markAsFailed() {
        this.status = OrderStatus.FAILED;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isPending() {
        return this.status == OrderStatus.PENDING;
    }

    public boolean isPendingPayment() {
        return this.status == OrderStatus.PENDING_PAYMENT;
    }

    public boolean isPaid() {
        return this.status == OrderStatus.PAID;
    }

    /** 결제 가능한 상태인지 (PENDING 또는 PENDING_PAYMENT) */
    public boolean isPayable() {
        return this.status == OrderStatus.PENDING || this.status == OrderStatus.PENDING_PAYMENT;
    }

    private void validateStatusTransition(OrderStatus target) {
        if (this.status == target) {
            throw new IllegalStateException("이미 " + target.name() + " 상태입니다.");
        }
        if (this.status == OrderStatus.CANCELLED || this.status == OrderStatus.FAILED) {
            throw new IllegalStateException("취소 또는 실패 상태의 주문은 변경할 수 없습니다.");
        }
    }
}
