package com.paycore.billing.controller.dto;

import com.paycore.payment.domain.Payment;
import com.paycore.payment.pg.PgProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 빌링키 결제 완료 응답
 *
 * [설계 결정] 결제 이력 추적을 위해 Payment 정보 포함.
 * - impUid(PG 결제키)는 환불/취소 시 PaymentService.cancelPayment()에서 사용됨.
 * - merchantUid(주문번호)로 결제 내역 조회 가능.
 */
@Getter
@Builder
@Schema(description = "빌링키 결제 완료 응답")
public class BillingKeyChargeResponse {

    @Schema(description = "Payment ID (결제 내역 PK)")
    private final Long paymentId;

    @Schema(description = "PG사 결제 키 (환불 시 사용)")
    private final String impUid;

    @Schema(description = "가맹점 주문번호")
    private final String merchantUid;

    @Schema(description = "결제 금액")
    private final Long paidAmount;

    @Schema(description = "결제 수단")
    private final String payMethod;

    @Schema(description = "PG사")
    private final PgProvider pgProvider;

    @Schema(description = "결제 완료 시각")
    private final LocalDateTime createdAt;

    public static BillingKeyChargeResponse of(Payment payment) {
        return BillingKeyChargeResponse.builder()
                .paymentId(payment.getId())
                .impUid(payment.getImpUid())
                .merchantUid(payment.getMerchantUid())
                .paidAmount(payment.getPaidAmount())
                .payMethod(payment.getPayMethod())
                .pgProvider(payment.getPgProvider())
                .createdAt(payment.getCreatedAt())
                .build();
    }
}
