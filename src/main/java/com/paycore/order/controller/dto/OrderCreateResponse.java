package com.paycore.order.controller.dto;

import com.paycore.order.domain.Order;
import com.paycore.order.domain.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
@Schema(description = "주문 생성 응답")
public class OrderCreateResponse {

    @Schema(description = "주문 번호", example = "ORD-20260303-001")
    private String orderNo;

    @Schema(description = "주문 상태", example = "PENDING")
    private OrderStatus status;

    @Schema(description = "결제 금액", example = "30000")
    private Long totalAmount;

    @Schema(description = "주문 생성 시각")
    private LocalDateTime createdAt;

    public static OrderCreateResponse of(Order order) {
        return OrderCreateResponse.builder()
                .orderNo(order.getOrderNo())
                .status(order.getStatus())
                .totalAmount(order.getTotalAmount())
                .createdAt(order.getCreatedAt())
                .build();
    }
}
