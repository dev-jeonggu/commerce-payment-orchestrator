package com.paycore.payment.method;

import com.paycore.payment.method.cmd.*;

public interface PaymentMethodProcessor {

    PaymentMethod method();

    PaymentDetail processPayment(PaymentCommand command);

    CancelResult cancel(CancelCommand command);

    PaymentDetail getPaymentByTxId(String txId);

    PaymentDetail getPaymentByMerchantOrderId(String merchantOrderId);

    default BillingResult chargeBilling(BillingCommand command) {
        throw new UnsupportedOperationException(method() + " does not support billing");
    }

    default VirtualAccountResult issueVirtualAccount(VirtualAccountCommand command) {
        throw new UnsupportedOperationException(method() + " does not support virtual account");
    }
}
