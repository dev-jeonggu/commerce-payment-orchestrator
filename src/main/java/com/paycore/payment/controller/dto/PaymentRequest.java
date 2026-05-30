package com.paycore.payment.controller.dto;

import com.paycore.payment.method.PaymentMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@Schema(description = "결제 요청")
public class PaymentRequest {

    @NotBlank
    @Schema(description = "가맹점 ID", example = "A010084434")
    private String merchantId;

    @NotBlank
    @Schema(description = "가맹점 주문번호", example = "ORD-20260303-001")
    private String merchantOrderId;

    @NotNull
    @Schema(description = "결제 금액", example = "30000")
    private Long amount;

    @NotNull
    @Schema(description = "결제 수단", example = "CARD")
    private PaymentMethod paymentMethod;

    @Schema(description = "주문명", example = "상품 주문")
    private String orderName;
}
