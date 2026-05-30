package com.paycore.payment.controller.dto;

import com.paycore.payment.domain.Payment;
import com.paycore.payment.domain.PaymentStatus;
import com.paycore.payment.method.PaymentMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
@Schema(description = "결제 정보 응답")
public class PaymentResponse {

    @Schema(description = "가맹점 주문번호", example = "ORD-20260303-001")
    private String merchantOrderId;

    @Schema(description = "내부 트랜잭션 ID")
    private String txId;

    @Schema(description = "결제 수단", example = "CARD")
    private PaymentMethod paymentMethod;

    @Schema(description = "결제 금액", example = "30000")
    private Long paidAmount;

    @Schema(description = "취소 금액", example = "0")
    private Long cancelledAmount;

    @Schema(description = "결제 상태", example = "PAID")
    private PaymentStatus paymentStatus;

    @Schema(description = "결제 생성 시각")
    private LocalDateTime createdAt;

    public static PaymentResponse of(Payment payment) {
        return PaymentResponse.builder()
                .merchantOrderId(payment.getMerchantOrderId())
                .txId(payment.getTxId())
                .paymentMethod(payment.getPaymentMethod())
                .paidAmount(payment.getPaidAmount())
                .cancelledAmount(payment.getCancelledAmount())
                .paymentStatus(payment.getStatus())
                .createdAt(payment.getCreatedAt())
                .build();
    }
}
