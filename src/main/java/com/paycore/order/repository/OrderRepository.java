package com.paycore.order.repository;

import com.paycore.order.domain.Order;
import com.paycore.order.domain.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByOrderNo(String orderNo);

    /**
     * 스케줄러용: PENDING 상태이면서 지정 시간 이전에 생성된 주문 조회
     */
    @Query("SELECT o FROM Order o WHERE o.status = :status AND o.createdAt <= :threshold")
    List<Order> findByStatusAndCreatedAtBefore(
            @Param("status") OrderStatus status,
            @Param("threshold") LocalDateTime threshold
    );

    /**
     * 가상계좌 Webhook 누락 복구용: PENDING_PAYMENT 상태이면서 지정 시간 이전 주문 조회
     *
     * [용도] 가상계좌 발급 후 PG Webhook 누락 시 스케줄러가 PG 단건 조회로 입금 여부 확인.
     * dueDate 만료 전 주문만 조회 (만료된 건은 VirtualAccountExpiryScheduler가 처리).
     */
    @Query("SELECT o FROM Order o WHERE o.status = :status AND o.createdAt <= :threshold")
    List<Order> findPendingPaymentOrdersBefore(
            @Param("status") OrderStatus status,
            @Param("threshold") LocalDateTime threshold
    );
}
