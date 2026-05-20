package com.paycore.payment.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

/**
 * PortOne 빌링키 결제 요청 (POST /subscribe/payments/again)
 */
@Getter
@Builder
public class PortOneSubscribePaymentRequest {

    @JsonProperty("customer_uid")
    private String customerUid;

    @JsonProperty("merchant_uid")
    private String merchantUid;

    private Long amount;

    private String name;
}
