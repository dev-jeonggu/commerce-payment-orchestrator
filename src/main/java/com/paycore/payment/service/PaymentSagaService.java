package com.paycore.payment.service;

import com.paycore.common.exception.ErrorCode;
import com.paycore.common.exception.PaycoreException;
import com.paycore.payment.domain.Payment;
import com.paycore.payment.domain.PaymentLog;
import com.paycore.payment.method.PaymentMethodRouter;
import com.paycore.payment.method.cmd.CancelCommand;
import com.paycore.payment.repository.PaymentRepository;
import com.paycore.saga.domain.SagaDeadLetter;
import com.paycore.saga.service.SagaDeadLetterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 보상 트랜잭션(Saga) 서비스
 *
 * [핵심 설계] Propagation.REQUIRES_NEW
 * 메인 TX와 독립적으로 보상 취소를 커밋.
 * cancelBySaga 실패 시 Dead Letter 저장 + 알람.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentSagaService {

    private final PaymentRepository paymentRepository;
    private final PaymentMethodRouter paymentMethodRouter;
    private final PaymentLogService paymentLogService;
    private final SagaDeadLetterService sagaDeadLetterService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cancelBySaga(String merchantOrderId) {
        log.info("[Saga] 보상 취소 시작 - merchantOrderId: {}", merchantOrderId);

        Payment payment = paymentRepository.findByMerchantOrderId(merchantOrderId)
                .orElseThrow(() -> new PaycoreException(ErrorCode.PAYMENT_NOT_FOUND));

        CancelCommand cancelCmd = CancelCommand.builder()
                .paymentKey(payment.getTxId())
                .orderId(merchantOrderId)
                .reason("결제 후처리 실패로 인한 자동 취소 (Saga)")
                .build();

        try {
            paymentMethodRouter.route(payment.getPaymentMethod()).cancel(cancelCmd);
            payment.cancel(payment.getPaidAmount());
            log.info("[Saga] 보상 취소 완료 - merchantOrderId: {}", merchantOrderId);
        } catch (Exception e) {
            log.error("[Saga] 보상 취소 실패 - merchantOrderId: {}", merchantOrderId, e);
            saveToDlq(merchantOrderId, payment.getTxId(), payment, e);
            throw e;
        }

        paymentLogService.saveLog(merchantOrderId, PaymentLog.LogType.PAYMENT_CANCEL,
                cancelCmd, null, true, "Saga compensation cancel");
    }

    private void saveToDlq(String merchantOrderId, String txId, Payment payment, Exception cause) {
        try {
            SagaDeadLetter deadLetter = SagaDeadLetter.builder()
                    .merchantOrderId(merchantOrderId)
                    .txId(txId)
                    .paymentMethod(payment.getPaymentMethod())
                    .errorMessage(cause.getMessage())
                    .build();
            sagaDeadLetterService.save(deadLetter);
        } catch (Exception dlqEx) {
            log.error("[Saga] DLQ 저장마저 실패 - merchantOrderId: {} (즉각 수동 처리 필요)",
                    merchantOrderId, dlqEx);
        }
    }
}
