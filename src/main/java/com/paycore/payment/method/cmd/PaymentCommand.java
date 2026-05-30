package com.paycore.payment.method.cmd;

import com.paycore.payment.method.PaymentMethod;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PaymentCommand {
    private final String merchantId;
    private final String merchantOrderId;
    private final Long amount;
    private final PaymentMethod paymentMethod;
    private final String orderName;
}
