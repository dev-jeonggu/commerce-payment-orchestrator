package com.paycore.payment.pg;

import lombok.Builder;
import lombok.Getter;

/**
 * PG사 공통 취소 요청
 *
 * amount=null → 전액 취소
 * amount>0    → 부분 취소
 */
@Getter
@Builder
public class PgCancelCommand {
    private final String paymentKey;  // imp_uid / paymentKey
    private final String orderId;     // merchant_uid
    private final Long amount;        // null = 전액 취소
    private final String reason;
}
