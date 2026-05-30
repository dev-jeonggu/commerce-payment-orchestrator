package com.paycore.payment.method.card;

import com.paycore.payment.method.PaymentMethod;
import com.paycore.payment.method.PaymentMethodProcessor;
import com.paycore.payment.method.cmd.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
public class CardPaymentProcessor implements PaymentMethodProcessor {

    @Override
    public PaymentMethod method() {
        return PaymentMethod.CARD;
    }

    @Override
    public PaymentDetail processPayment(PaymentCommand command) {
        String txId = "CARD-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        log.info("[CardPaymentProcessor] 카드 결제 처리 - merchantOrderId: {}, amount: {}, txId: {}",
                command.getMerchantOrderId(), command.getAmount(), txId);
        return PaymentDetail.builder()
                .paymentKey(txId)
                .orderId(command.getMerchantOrderId())
                .status("paid")
                .payMethod("card")
                .amount(command.getAmount())
                .cancelledAmount(0L)
                .paidAt(System.currentTimeMillis() / 1000)
                .build();
    }

    @Override
    public CancelResult cancel(CancelCommand command) {
        log.info("[CardPaymentProcessor] 카드 결제 취소 - paymentKey: {}, amount: {}",
                command.getPaymentKey(), command.getAmount());
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
        log.info("[CardPaymentProcessor] txId로 결제 조회 - txId: {}", txId);
        return PaymentDetail.builder()
                .paymentKey(txId)
                .status("paid")
                .payMethod("card")
                .build();
    }

    @Override
    public PaymentDetail getPaymentByMerchantOrderId(String merchantOrderId) {
        log.info("[CardPaymentProcessor] merchantOrderId로 결제 조회 - merchantOrderId: {}", merchantOrderId);
        return PaymentDetail.builder()
                .orderId(merchantOrderId)
                .status("paid")
                .payMethod("card")
                .build();
    }
}
