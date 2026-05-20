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

        private String status;  // paid, cancelled, failed, ready

        private Long amount;

        @JsonProperty("cancel_amount")
        private Long cancelAmount;

        @JsonProperty("paid_at")
        private Long paidAt;

        // 가상계좌 필드 (pay_method=vbank 일 때)
        @JsonProperty("vbank_code")
        private String vbankCode;

        @JsonProperty("vbank_name")
        private String vbankName;

        @JsonProperty("vbank_num")
        private String vbankNum;

        @JsonProperty("vbank_holder")
        private String vbankHolder;

        @JsonProperty("vbank_date")
        private Long vbankDate;   // 입금 기한 Unix timestamp

        // 빌링키 관련
        @JsonProperty("customer_uid")
        private String customerUid;
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
