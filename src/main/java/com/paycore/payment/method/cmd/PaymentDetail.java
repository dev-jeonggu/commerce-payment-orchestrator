package com.paycore.payment.method.cmd;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PaymentDetail {

    private final String paymentKey;
    private final String orderId;
    private final String status;
    private final String payMethod;
    private final Long amount;
    private final Long cancelledAmount;
    private final Long paidAt;
    private final VirtualAccountInfo virtualAccountInfo;

    public boolean isPaid() {
        return "paid".equals(status);
    }

    public boolean isCancelled() {
        return "cancelled".equals(status);
    }

    public boolean isVirtualAccount() {
        return "vbank".equals(payMethod);
    }

    @Getter
    @Builder
    public static class VirtualAccountInfo {
        private final String bankCode;
        private final String bankName;
        private final String accountNumber;
        private final String holderName;
        private final Long dueDate;
    }
}
