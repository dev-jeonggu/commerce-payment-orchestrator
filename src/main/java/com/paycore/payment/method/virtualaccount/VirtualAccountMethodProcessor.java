package com.paycore.payment.method.virtualaccount;

import com.paycore.payment.method.PaymentMethod;
import com.paycore.payment.method.PaymentMethodProcessor;
import com.paycore.payment.method.cmd.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Component
public class VirtualAccountMethodProcessor implements PaymentMethodProcessor {

    @Override
    public PaymentMethod method() {
        return PaymentMethod.VIRTUAL_ACCOUNT;
    }

    @Override
    public PaymentDetail processPayment(PaymentCommand command) {
        String txId = "VA-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        log.info("[VirtualAccountMethodProcessor] 가상계좌 결제 처리 - merchantOrderId: {}, txId: {}",
                command.getMerchantOrderId(), txId);
        return PaymentDetail.builder()
                .paymentKey(txId)
                .orderId(command.getMerchantOrderId())
                .status("ready")
                .payMethod("vbank")
                .amount(command.getAmount())
                .cancelledAmount(0L)
                .build();
    }

    @Override
    public CancelResult cancel(CancelCommand command) {
        log.info("[VirtualAccountMethodProcessor] 가상계좌 취소 - paymentKey: {}", command.getPaymentKey());
        return CancelResult.builder()
                .paymentKey(command.getPaymentKey())
                .cancelledAmount(command.getAmount())
                .remainingAmount(0L)
                .status("cancelled")
                .build();
    }

    @Override
    public PaymentDetail getPaymentByTxId(String txId) {
        return PaymentDetail.builder().paymentKey(txId).status("ready").payMethod("vbank").build();
    }

    @Override
    public PaymentDetail getPaymentByMerchantOrderId(String merchantOrderId) {
        return PaymentDetail.builder().orderId(merchantOrderId).status("ready").payMethod("vbank").build();
    }

    @Override
    public VirtualAccountResult issueVirtualAccount(VirtualAccountCommand command) {
        String accountNumber = "1234-" + System.currentTimeMillis() % 100000000;
        log.info("[VirtualAccountMethodProcessor] 가상계좌 발급 - orderId: {}, bank: {}",
                command.getOrderId(), command.getBankCode());
        return VirtualAccountResult.builder()
                .paymentKey("VA-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase())
                .bankCode(command.getBankCode())
                .bankName("테스트은행")
                .accountNumber(accountNumber)
                .holderName(command.getHolderName())
                .dueDate(command.getDueDate() != null ? command.getDueDate() : LocalDateTime.now().plusDays(3))
                .build();
    }
}
