package com.paycore.payment.method.cmd;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class CancelResult {
    private final String paymentKey;
    private final Long cancelledAmount;
    private final Long remainingAmount;
    private final String status;

    public static CancelResult of(String paymentKey, Long cancelledAmount, Long remainingAmount) {
        String status = remainingAmount == 0 ? "cancelled" : "partial_cancelled";
        return CancelResult.builder()
                .paymentKey(paymentKey)
                .cancelledAmount(cancelledAmount)
                .remainingAmount(remainingAmount)
                .status(status)
                .build();
    }
}
