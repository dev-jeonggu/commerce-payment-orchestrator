package com.paycore.payment.service;

import com.paycore.common.exception.ErrorCode;
import com.paycore.common.exception.PaycoreException;
import com.paycore.order.domain.Order;
import com.paycore.order.repository.OrderRepository;
import com.paycore.payment.domain.Payment;
import com.paycore.payment.domain.PaymentLog;
import com.paycore.payment.pg.PgCancelCommand;
import com.paycore.payment.pg.PgRouter;
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
 * - processAfterPayment 실패 시 메인 TX와 독립적으로 보상 취소 커밋
 *
 * [Step 4 변경] cancelBySaga 실패 시 Dead Letter 저장 + 알람
 * - SagaDeadLetterService.save()도 REQUIRES_NEW → DLQ 저장은 반드시 커밋
 *
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentSagaService {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PgRouter pgRouter;
    private final PaymentLogService paymentLogService;
    private final SagaDeadLetterService sagaDeadLetterService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cancelBySaga(String orderNo) {
        log.info("[Saga] 보상 취소 시작 - orderNo: {}", orderNo);

        Order order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new PaycoreException(ErrorCode.ORDER_NOT_FOUND));
        Payment payment = paymentRepository.findByMerchantUid(orderNo)
                .orElseThrow(() -> new PaycoreException(ErrorCode.PAYMENT_NOT_FOUND));

        // [버그 수정] 로그 저장(paymentLogService.saveLog)을 critical section 밖으로 분리.
        // 기존 코드에서는 saveLog가 DataAccessException 등을 던지면 catch → throw e →
        // REQUIRES_NEW TX 롤백 → payment.cancel() + order.markAsCancelled() 원복.
        // 결과: PG는 취소됐는데 DB는 PAID 상태로 남는 치명적 불일치 발생.
        PgCancelCommand cancelCmd = PgCancelCommand.builder()
                .paymentKey(payment.getImpUid())
                .orderId(orderNo)
                .reason("결제 후처리 실패로 인한 자동 취소 (Saga)")
                .build();

        try {
            // Critical section: PG 취소 + DB 상태 변경 (로그 저장 제외)
            pgRouter.route(order.getPgProvider()).cancel(cancelCmd);
            payment.cancel(payment.getPaidAmount());
            order.markAsCancelled();
            log.info("[Saga] 보상 취소 완료 - orderNo: {}", orderNo);

        } catch (Exception e) {
            log.error("[Saga] 보상 취소 실패 - orderNo: {}", orderNo, e);

            // REQUIRES_NEW TX로 DLQ 저장 (현재 TX 롤백과 무관하게 커밋)
            saveToDlq(orderNo, payment.getImpUid(), order, e);
            throw e;
        }

        // 로그 저장: critical section 외부 실행 → 실패해도 보상 취소 TX는 이미 커밋됨
        // (PaymentLogService.saveLog도 내부적으로 모든 예외 흡수하여 이중 방어)
        paymentLogService.saveLog(orderNo, PaymentLog.LogType.PAYMENT_CANCEL,
                cancelCmd, null, true, "Saga compensation cancel");
    }

    private void saveToDlq(String orderNo, String impUid, Order order, Exception cause) {
        try {
            SagaDeadLetter deadLetter = SagaDeadLetter.builder()
                    .orderNo(orderNo)
                    .impUid(impUid)
                    .pgProvider(order.getPgProvider())
                    .errorMessage(cause.getMessage())
                    .build();
            sagaDeadLetterService.save(deadLetter);
        } catch (Exception dlqEx) {
            log.error("[Saga] DLQ 저장마저 실패 - orderNo: {} (즉각 수동 처리 필요)", orderNo, dlqEx);
        }
    }
}
