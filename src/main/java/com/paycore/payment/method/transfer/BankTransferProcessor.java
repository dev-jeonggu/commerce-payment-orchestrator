package com.paycore.payment.method.transfer;

import com.paycore.payment.method.PaymentMethod;
import com.paycore.payment.method.PaymentMethodProcessor;
import com.paycore.payment.method.cmd.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
public class BankTransferProcessor implements PaymentMethodProcessor {

    @Override
    public PaymentMethod method() {
        return PaymentMethod.BANK_TRANSFER;
    }

    @Override
    public PaymentDetail processPayment(PaymentCommand command) {
        String txId = "BT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        log.info("[BankTransferProcessor] 계좌이체 처리 - merchantOrderId: {}, txId: {}",
                command.getMerchantOrderId(), txId);
        return PaymentDetail.builder()
                .paymentKey(txId)
                .orderId(command.getMerchantOrderId())
                .status("paid")
                .payMethod("trans")
                .amount(command.getAmount())
                .cancelledAmount(0L)
                .paidAt(System.currentTimeMillis() / 1000)
                .build();
    }

    @Override
    public CancelResult cancel(CancelCommand command) {
        log.info("[BankTransferProcessor] 계좌이체 취소 - paymentKey: {}", command.getPaymentKey());
        long cancelled = command.getAmount() != null ? command.getAmount() : 0L;
        return CancelResult.builder()
                .paymentKey(command.getPaymentKey())
                .cancelledAmount(cancelled)
                .remainingAmount(0L)
                .status("cancelled")
                .build();
    }

    @Override
    public PaymentDetail getPaymentByTxId(String txId) {
        return PaymentDetail.builder().paymentKey(txId).status("paid").payMethod("trans").build();
    }

    @Override
    public PaymentDetail getPaymentByMerchantOrderId(String merchantOrderId) {
        return PaymentDetail.builder().orderId(merchantOrderId).status("paid").payMethod("trans").build();
    }
}
