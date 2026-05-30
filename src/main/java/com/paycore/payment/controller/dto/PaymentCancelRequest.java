package com.paycore.payment.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@Schema(description = "결제 취소 요청")
public class PaymentCancelRequest {

    @NotBlank(message = "merchantOrderId는 필수입니다.")
    @Schema(description = "가맹점 주문번호", example = "ORD-20260303-001")
    private String merchantOrderId;

    @NotBlank(message = "취소 사유는 필수입니다.")
    @Schema(description = "취소 사유", example = "고객 요청")
    private String reason;

    @Positive(message = "취소 금액은 양수여야 합니다.")
    @Schema(description = "취소 금액 (null이면 전액 취소)", example = "30000")
    private Long amount;
}
