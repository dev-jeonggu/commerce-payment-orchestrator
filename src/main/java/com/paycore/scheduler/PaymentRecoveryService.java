package com.paycore.scheduler;

import com.paycore.order.domain.Order;
import com.paycore.order.repository.OrderRepository;
import com.paycore.payment.client.PortOneClient;
import com.paycore.payment.client.dto.PortOnePaymentResponse;
import com.paycore.payment.domain.Payment;
import com.paycore.payment.domain.PaymentLog;
import com.paycore.payment.repository.PaymentRepository;
import com.paycore.payment.service.PaymentLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 스케줄러 복구 트랜잭션 서비스
 *
 * [설계 이유] PaymentRecoveryScheduler와 분리한 핵심 이유:
 * Spring AOP 트랜잭션은 프록시를 통해서만 동작한다.
 * 같은 클래스 내 메서드 직접 호출(self-invocation)은 프록시를 우회하므로
 * @Transactional이 무시된다.
 *
 * 이 Bean을 별도로 분리하면 스케줄러가 프록시를 통해 호출하게 되어
 * Payment 저장 + Order 상태 변경이 하나의 트랜잭션으로 원자적으로 커밋된다.
 * 커밋 완료 후 processAfterPayment가 실행되므로
 * Saga 보상 트랜잭션(REQUIRES_NEW)이 커밋된 Payment를 정상 조회할 수 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentRecoveryService {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PortOneClient portOneClient;
    private final PaymentLogService paymentLogService;

    /**
     * Payment 저장 + Order 상태 변경을 단일 트랜잭션으로 커밋
     *
     * @return true: PAID 복구 성공 (processAfterPayment 호출 필요)
     *         false: CANCELLED 처리 또는 이미 처리된 경우
     */
    @Transactional
    public boolean recoverOrderWithTx(Order order) {
        log.info("[RecoveryService] 복구 처리 - orderNo: {}, createdAt: {}",
                order.getOrderNo(), order.getCreatedAt());

        // findByStatusAndCreatedAtBefore()의 트랜잭션이 끝나면 order는 detached 상태가 된다.
        // 새 트랜잭션에서 order를 다시 로드하여 managed 상태로 만들어 dirty checking이 동작하게 한다.
        Order managedOrder = orderRepository.findByOrderNo(order.getOrderNo())
                .orElseThrow(() -> new IllegalStateException("복구 대상 주문 미조회: " + order.getOrderNo()));

        PortOnePaymentResponse pgPayment;
        try {
            pgPayment = portOneClient.getPaymentByMerchantUid(managedOrder.getOrderNo());
        } catch (Exception e) {
            log.warn("[RecoveryService] PG 조회 실패 - orderNo: {} → CANCELLED 처리", managedOrder.getOrderNo());
            managedOrder.markAsCancelled();
            paymentLogService.saveLog(managedOrder.getOrderNo(), PaymentLog.LogType.SCHEDULER_RECOVERY,
                    null, null, false, "PG 조회 실패: " + e.getMessage());
            return false;
        }

        if (pgPayment.isPaid()) {
            if (paymentRepository.existsByImpUid(pgPayment.getResponse().getImpUid())) {
                log.info("[RecoveryService] 이미 처리된 결제 스킵 - orderNo: {}", managedOrder.getOrderNo());
                return false;
            }
            Payment payment = Payment.builder()
                    .orderId(managedOrder.getId())
                    .impUid(pgPayment.getResponse().getImpUid())
                    .merchantUid(managedOrder.getOrderNo())
                    .payMethod(pgPayment.getResponse().getPayMethod())
                    .paidAmount(pgPayment.getAmount())
                    .build();
            paymentRepository.save(payment);
            managedOrder.markAsPaid();
            paymentLogService.saveLog(managedOrder.getOrderNo(), PaymentLog.LogType.SCHEDULER_RECOVERY,
                    null, pgPayment, true, null);
            log.info("[RecoveryService] PAID 복구 완료 - orderNo: {}", managedOrder.getOrderNo());
            return true;
        } else {
            managedOrder.markAsCancelled();
            paymentLogService.saveLog(managedOrder.getOrderNo(), PaymentLog.LogType.SCHEDULER_RECOVERY,
                    null, pgPayment, true, null);
            log.info("[RecoveryService] CANCELLED 처리 - orderNo: {}", managedOrder.getOrderNo());
            return false;
        }
    }
}
