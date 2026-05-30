package com.paycore.billing.controller.dto;

import com.paycore.payment.domain.Payment;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
@Schema(description = "빌링키 결제 완료 응답")
public class BillingKeyChargeResponse {

    @Schema(description = "Payment ID")
    private final Long paymentId;

    @Schema(description = "내부 트랜잭션 ID")
    private final String txId;

    @Schema(description = "가맹점 주문번호")
    private final String merchantOrderId;

    @Schema(description = "결제 금액")
    private final Long paidAmount;

    @Schema(description = "결제 완료 시각")
    private final LocalDateTime createdAt;

    public static BillingKeyChargeResponse of(Payment payment) {
        return BillingKeyChargeResponse.builder()
                .paymentId(payment.getId())
                .txId(payment.getTxId())
                .merchantOrderId(payment.getMerchantOrderId())
                .paidAmount(payment.getPaidAmount())
                .createdAt(payment.getCreatedAt())
                .build();
    }
}
