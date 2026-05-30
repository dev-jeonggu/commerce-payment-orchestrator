package com.paycore.payment.method.cmd;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class VirtualAccountResult {
    private final String paymentKey;
    private final String bankCode;
    private final String bankName;
    private final String accountNumber;
    private final String holderName;
    private final LocalDateTime dueDate;
}
