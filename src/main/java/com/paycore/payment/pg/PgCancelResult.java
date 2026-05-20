package com.paycore.payment.pg;

import lombok.Builder;
import lombok.Getter;

/**
 * PG사 공통 취소 응답
 */
@Getter
@Builder
public class PgCancelResult {
    private final String paymentKey;
    private final Long cancelledAmount;
    private final Long remainingAmount;
    private final String status;

    public static PgCancelResult of(String paymentKey, Long cancelledAmount, Long remainingAmount) {
        String status = remainingAmount == 0 ? "cancelled" : "partial_cancelled";
        return PgCancelResult.builder()
                .paymentKey(paymentKey)
                .cancelledAmount(cancelledAmount)
                .remainingAmount(remainingAmount)
                .status(status)
                .build();
    }
}
