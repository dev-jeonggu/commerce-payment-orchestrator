package com.paycore.order.domain;

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

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Builder
    public Order(String orderNo, Long userId, Long itemId, Long totalAmount) {
        this.orderNo = orderNo;
        this.userId = userId;
        this.itemId = itemId;
        this.totalAmount = totalAmount;
        this.status = OrderStatus.PENDING;
    }

    public void markAsPaid() {
        validateStatusTransition(OrderStatus.PAID);
        this.status = OrderStatus.PAID;
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

    public boolean isPaid() {
        return this.status == OrderStatus.PAID;
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
