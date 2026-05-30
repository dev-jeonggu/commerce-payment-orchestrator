package com.paycore.payment.method.cmd;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class VirtualAccountCommand {
    private final String orderId;
    private final Long amount;
    private final String orderName;
    private final String bankCode;
    private final String holderName;
    private final LocalDateTime dueDate;
}
