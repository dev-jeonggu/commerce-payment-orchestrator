package com.paycore.payment.method.cmd;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class BillingResult {
    private final String paymentKey;
    private final String orderId;
    private final Long amount;
    private final String status;
    private final String payMethod;
}
