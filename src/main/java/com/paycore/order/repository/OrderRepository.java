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
}
