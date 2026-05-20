package com.paycore.saga.service;

import com.paycore.notification.AlertService;
import com.paycore.order.domain.Order;
import com.paycore.order.repository.OrderRepository;
import com.paycore.payment.domain.Payment;
import com.paycore.payment.pg.PgCancelCommand;
import com.paycore.payment.pg.PgRouter;
import com.paycore.payment.repository.PaymentRepository;
import com.paycore.saga.domain.SagaDeadLetter;
import com.paycore.saga.domain.SagaDeadLetterStatus;
import com.paycore.saga.repository.SagaDeadLetterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PgRouter pgRouter;
    private final AlertService alertService;

    /**
     * DLQ에 실패 기록 저장
     * cancelBySaga 실패 catch 블록에서 호출
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(SagaDeadLetter deadLetter) {
        deadLetterRepository.save(deadLetter);
        log.error("[DLQ] Dead Letter 저장 - orderNo: {}, impUid: {}",
                deadLetter.getOrderNo(), deadLetter.getImpUid());
        alertService.sendCritical(
                "Saga 보상 취소 실패 - 즉시 확인 필요",
                deadLetter.getOrderNo(),
                deadLetter.getErrorMessage()
        );
    }

    /**
     * PENDING 상태 Dead Letter 재시도
     * DeadLetterRetryScheduler에서 호출
     *
     * [처리 순서]
     * 1. DLQ 상태 → PROCESSING (재시도 중)
     * 2. PG API 취소 호출
     * 3. 성공 시: Order.CANCELLED + Payment.CANCELLED + DLQ.RESOLVED
     * 4. 실패 시: DLQ.PENDING(재시도) 또는 DLQ.EXHAUSTED(최대 초과)
     *
     * [주의] PG API 호출이 @Transactional 안에 있어 커넥션을 잡고 있음.
     * 대량 DLQ 처리 시 커넥션 풀 고갈 주의 (운영 환경 DLQ 건수 모니터링 권장).
     */
    @Transactional
    public void retry(SagaDeadLetter deadLetter) {
        deadLetter.markProcessing();
        deadLetterRepository.save(deadLetter);

        try {
            pgRouter.route(deadLetter.getPgProvider()).cancel(
                    PgCancelCommand.builder()
                            .paymentKey(deadLetter.getImpUid())
                            .orderId(deadLetter.getOrderNo())
                            .reason("Saga 보상 취소 재시도 (DLQ)")
                            .build()
            );

            // PG 취소 성공 → Order/Payment DB 상태 동기화
            syncCancelledState(deadLetter.getOrderNo(), deadLetter.getImpUid());

            deadLetter.markResolved();
            deadLetterRepository.save(deadLetter);
            log.info("[DLQ] 재시도 성공 - orderNo: {}", deadLetter.getOrderNo());

        } catch (Exception e) {
            deadLetter.markFailed(e.getMessage());
            deadLetterRepository.save(deadLetter);

            if (deadLetter.isExhausted()) {
                log.error("[DLQ] 최대 재시도 초과 - orderNo: {} (수동 처리 필요)", deadLetter.getOrderNo());
                alertService.sendCritical(
                        "DLQ 최대 재시도 초과 - 수동 처리 필요",
                        deadLetter.getOrderNo(),
                        "attemptCount=" + deadLetter.getAttemptCount() + ", error=" + e.getMessage()
                );
            } else {
                log.warn("[DLQ] 재시도 실패 ({}회) - orderNo: {}",
                        deadLetter.getAttemptCount(), deadLetter.getOrderNo());
            }
        }
    }

    /**
     * PG 취소 성공 후 Order/Payment 상태를 CANCELLED로 동기화
     *
     * [방어 처리]
     * - Order/Payment가 이미 CANCELLED이면 skip (다른 경로로 처리됐을 수 있음)
     * - Payment가 없으면 Order만 처리 (cancelBySaga는 Payment 없는 경우도 있을 수 있음)
     * - 동기화 실패는 PG 취소 자체의 성공/실패에 영향 없음
     *   (DLQ.RESOLVED는 PG 취소 성공 기준으로 판정)
     */
    private void syncCancelledState(String orderNo, String impUid) {
        try {
            orderRepository.findByOrderNo(orderNo).ifPresent(order -> {
                if (!order.isPaid()) {
                    log.debug("[DLQ] Order 이미 CANCELLED 또는 다른 상태 - skip - orderNo: {}", orderNo);
                    return;
                }
                order.markAsCancelled();
                log.info("[DLQ] Order 상태 CANCELLED 동기화 - orderNo: {}", orderNo);
            });

            paymentRepository.findByImpUid(impUid).ifPresent(payment -> {
                if (payment.getStatus() == com.paycore.payment.domain.PaymentStatus.CANCELLED) {
                    log.debug("[DLQ] Payment 이미 CANCELLED - skip - impUid: {}", impUid);
                    return;
                }
                payment.cancel(payment.getPaidAmount());
                log.info("[DLQ] Payment 상태 CANCELLED 동기화 - impUid: {}", impUid);
            });

        } catch (Exception e) {
            // 상태 동기화 실패는 PG 취소 성공에 영향 없음.
            // 단, DB 불일치가 남으므로 Critical 알람.
            log.error("[DLQ] Order/Payment 상태 동기화 실패 (PG 취소는 성공) - orderNo: {} (수동 처리 필요)",
                    orderNo, e);
            alertService.sendCritical(
                    "DLQ 재시도 성공 후 DB 상태 동기화 실패 - 수동 처리 필요",
                    orderNo,
                    "PG 취소 완료, DB 상태 미반영: " + e.getMessage()
            );
        }
    }

    public List<SagaDeadLetter> findPending() {
        return deadLetterRepository.findByStatusOrderByCreatedAtAsc(SagaDeadLetterStatus.PENDING);
    }
}
