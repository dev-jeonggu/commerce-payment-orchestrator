package com.paycore.payment.pg;

import lombok.Builder;
import lombok.Getter;

/**
 * PG사 공통 빌링키 결제 응답
 */
@Getter
@Builder
public class PgBillingResult {
    private final String paymentKey;
    private final String orderId;
    private final Long amount;
    private final String status;
    private final String payMethod;
}
