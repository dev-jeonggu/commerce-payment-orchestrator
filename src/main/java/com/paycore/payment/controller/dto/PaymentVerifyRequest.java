package com.paycore.payment.controller.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@Schema(description = "결제 검증 요청")
public class PaymentVerifyRequest {

    @NotBlank(message = "imp_uid는 필수입니다.")
    @JsonProperty("imp_uid")
    @Schema(description = "PG사 고유 결제번호", example = "imp_123456789")
    private String impUid;

    @NotBlank(message = "merchant_uid는 필수입니다.")
    @JsonProperty("merchant_uid")
    @Schema(description = "가맹점 주문번호 (orderNo)", example = "ORD-20260303-001")
    private String merchantUid;
}
