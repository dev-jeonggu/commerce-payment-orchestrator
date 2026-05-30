package com.paycore.payment.method.cmd;

import lombok.Builder;
import lombok.Getter;

/**
 * amount=null → 전액 취소
 * amount>0    → 부분 취소
 */
@Getter
@Builder
public class CancelCommand {
    private final String paymentKey;
    private final String orderId;
    private final Long amount;
    private final String reason;
}
