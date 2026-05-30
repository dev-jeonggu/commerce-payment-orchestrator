package com.paycore.billing.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@Schema(description = "빌링키 결제 요청 (자동결제/정기결제)")
public class BillingKeyChargeRequest {

    @NotBlank
    @Schema(description = "가맹점 ID", example = "A010084434")
    private String merchantId;

    @NotNull
    @Schema(description = "사용자 ID", example = "1")
    private Long userId;

    @NotNull
    @Schema(description = "빌링키 ID", example = "5")
    private Long billingKeyId;

    @NotBlank
    @Schema(description = "가맹점 주문번호", example = "ORD-20260519-A1B2C3")
    private String merchantOrderId;

    @NotNull
    @Min(100)
    @Schema(description = "결제 금액", example = "9900")
    private Long amount;

    @NotBlank
    @Schema(description = "주문명 (영수증 표시)", example = "프리미엄 구독 2026년 5월")
    private String orderName;
}
