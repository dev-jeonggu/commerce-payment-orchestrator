package com.paycore.payment.controller.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@Schema(description = "PG사 Webhook 요청 (신뢰하지 않음 - 단건 조회로 재검증)")
public class PaymentWebhookRequest {

    @JsonProperty("imp_uid")
    @Schema(description = "PG사 결제번호")
    private String impUid;

    @JsonProperty("merchant_uid")
    @Schema(description = "가맹점 주문번호")
    private String merchantUid;

    @Schema(description = "결제 상태 (Webhook 내용 신뢰하지 않음, 재조회로 검증)")
    private String status;
}
