package com.paycore.payment.controller.dto;

import com.paycore.order.domain.Order;
import com.paycore.order.domain.OrderStatus;
import com.paycore.payment.domain.Payment;
import com.paycore.payment.domain.PaymentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
@Schema(description = "결제 정보 응답")
public class PaymentResponse {

    @Schema(description = "주문번호", example = "ORD-20260303-001")
    private String orderNo;

    @Schema(description = "주문 상태", example = "PAID")
    private OrderStatus orderStatus;

    @Schema(description = "PG사 결제번호", example = "imp_123456789")
    private String impUid;

    @Schema(description = "결제 수단", example = "card")
    private String payMethod;

    @Schema(description = "결제 금액", example = "30000")
    private Long paidAmount;

    @Schema(description = "취소 금액", example = "0")
    private Long cancelledAmount;

    @Schema(description = "결제 상태", example = "PAID")
    private PaymentStatus paymentStatus;

    @Schema(description = "결제 생성 시각")
    private LocalDateTime createdAt;

    public static PaymentResponse of(Payment payment, Order order) {
        return PaymentResponse.builder()
                .orderNo(order.getOrderNo())
                .orderStatus(order.getStatus())
                .impUid(payment.getImpUid())
                .payMethod(payment.getPayMethod())
                .paidAmount(payment.getPaidAmount())
                .cancelledAmount(payment.getCancelledAmount())
                .paymentStatus(payment.getStatus())
                .createdAt(payment.getCreatedAt())
                .build();
    }
}
