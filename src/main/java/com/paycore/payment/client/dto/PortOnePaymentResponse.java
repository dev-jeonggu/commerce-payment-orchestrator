package com.paycore.payment.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class PortOnePaymentResponse {

    private int code;
    private String message;
    private PaymentData response;

    @Getter
    @NoArgsConstructor
    public static class PaymentData {
        @JsonProperty("imp_uid")
        private String impUid;

        @JsonProperty("merchant_uid")
        private String merchantUid;

        @JsonProperty("pay_method")
        private String payMethod;

        private String status;  // paid, cancelled, failed

        private Long amount;

        @JsonProperty("cancel_amount")
        private Long cancelAmount;

        @JsonProperty("paid_at")
        private Long paidAt;
    }

    public boolean isSuccess() {
        return code == 0;
    }

    public boolean isPaid() {
        return response != null && "paid".equals(response.getStatus());
    }

    public Long getAmount() {
        return response != null ? response.getAmount() : null;
    }
}
