package com.paycore.saga.service;

import com.paycore.notification.AlertService;
import com.paycore.payment.domain.Payment;
import com.paycore.payment.method.PaymentMethodRouter;
import com.paycore.payment.method.cmd.CancelCommand;
import com.paycore.payment.repository.PaymentRepository;
import com.paycore.saga.domain.SagaDeadLetter;
import com.paycore.saga.domain.SagaDeadLetterStatus;
import com.paycore.saga.repository.SagaDeadLetterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Pageable;
import java.util.List;

/**
 * Saga Dead Letter 관리 서비스
 *
 * [REQUIRES_NEW 이유]
 * cancelBySaga가 실패한 TX 컨텍스트 안에서 호출됨.
 * 메인 TX가 롤백되어도 DLQ 저장은 독립적으로 커밋되어야 함.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SagaDeadLetterService {

    private final SagaDeadLetterRepository deadLetterRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentMethodRouter paymentMethodRouter;
    private final AlertService alertService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(SagaDeadLetter deadLetter) {
        deadLetterRepository.save(deadLetter);
        log.error("[DLQ] Dead Letter 저장 - merchantOrderId: {}, txId: {}",
                deadLetter.getMerchantOrderId(), deadLetter.getTxId());
        alertService.sendCritical(
                "Saga 보상 취소 실패 - 즉시 확인 필요",
                deadLetter.getMerchantOrderId(),
                deadLetter.getErrorMessage()
        );
    }

    /**
     * PENDING 상태 Dead Letter 재시도
     */
    @Transactional
    public void retry(SagaDeadLetter deadLetter) {
        deadLetter.markProcessing();
        deadLetterRepository.save(deadLetter);

        try {
            paymentMethodRouter.route(deadLetter.getPaymentMethod()).cancel(
                    CancelCommand.builder()
                            .paymentKey(deadLetter.getTxId())
                            .orderId(deadLetter.getMerchantOrderId())
                            .reason("Saga 보상 취소 재시도 (DLQ)")
                            .build()
            );

            syncCancelledState(deadLetter.getMerchantOrderId());

            deadLetter.markResolved();
            deadLetterRepository.save(deadLetter);
            log.info("[DLQ] 재시도 성공 - merchantOrderId: {}", deadLetter.getMerchantOrderId());

        } catch (Exception e) {
            deadLetter.markFailed(e.getMessage());
            deadLetterRepository.save(deadLetter);

            if (deadLetter.isExhausted()) {
                log.error("[DLQ] 최대 재시도 초과 - merchantOrderId: {} (수동 처리 필요)", deadLetter.getMerchantOrderId());
                alertService.sendCritical(
                        "DLQ 최대 재시도 초과 - 수동 처리 필요",
                        deadLetter.getMerchantOrderId(),
                        "attemptCount=" + deadLetter.getAttemptCount() + ", error=" + e.getMessage()
                );
            } else {
                log.warn("[DLQ] 재시도 실패 ({}회) - merchantOrderId: {}",
                        deadLetter.getAttemptCount(), deadLetter.getMerchantOrderId());
            }
        }
    }

    private void syncCancelledState(String merchantOrderId) {
        try {
            paymentRepository.findByMerchantOrderId(merchantOrderId).ifPresent(payment -> {
                if (payment.getStatus() == com.paycore.payment.domain.PaymentStatus.CANCELLED) {
                    log.debug("[DLQ] Payment 이미 CANCELLED - skip - merchantOrderId: {}", merchantOrderId);
                    return;
                }
                payment.cancel(payment.getPaidAmount());
                log.info("[DLQ] Payment 상태 CANCELLED 동기화 - merchantOrderId: {}", merchantOrderId);
            });
        } catch (Exception e) {
            log.error("[DLQ] Payment 상태 동기화 실패 (외부기관 취소는 성공) - merchantOrderId: {} (수동 처리 필요)",
                    merchantOrderId, e);
            alertService.sendCritical(
                    "DLQ 재시도 성공 후 DB 상태 동기화 실패 - 수동 처리 필요",
                    merchantOrderId,
                    "외부기관 취소 완료, DB 상태 미반영: " + e.getMessage()
            );
        }
    }

    public List<SagaDeadLetter> findPending(Pageable pageable) {
        return deadLetterRepository.findByStatus(SagaDeadLetterStatus.PENDING, pageable).getContent();
    }
}
