package com.paycore.scheduler;

import com.paycore.order.domain.Order;
import com.paycore.order.repository.OrderRepository;
import com.paycore.payment.domain.Payment;
import com.paycore.payment.domain.PaymentLog;
import com.paycore.payment.pg.PgPaymentDetail;
import com.paycore.payment.pg.PgRouter;
import com.paycore.payment.repository.PaymentRepository;
import com.paycore.payment.service.PaymentLogService;
import com.paycore.virtualaccount.repository.VirtualAccountRepository;
import com.paycore.virtualaccount.service.VirtualAccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 스케줄러 복구 트랜잭션 서비스
 *
 * [설계 이유] PaymentRecoveryScheduler와 분리한 핵심 이유:
 * Spring AOP 트랜잭션은 프록시를 통해서만 동작.
 * self-invocation 시 @Transactional 무시 → 별도 Bean으로 분리.
 *
 * [변경] PortOneClient 직접 의존 제거 → PgRouter 경유
 * order.pgProvider로 해당 PG 클라이언트 선택
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentRecoveryService {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PgRouter pgRouter;
    private final PaymentLogService paymentLogService;
    private final VirtualAccountRepository virtualAccountRepository;

    @Transactional
    public boolean recoverOrderWithTx(Order order) {
        log.info("[RecoveryService] 복구 처리 - orderNo: {}, createdAt: {}",
                order.getOrderNo(), order.getCreatedAt());

        Order managedOrder = orderRepository.findByOrderNo(order.getOrderNo())
                .orElseThrow(() -> new IllegalStateException("복구 대상 주문 미조회: " + order.getOrderNo()));

        PgPaymentDetail pgPayment;
        try {
            pgPayment = pgRouter.route(managedOrder.getPgProvider())
                    .getPaymentByOrderId(managedOrder.getOrderNo());
        } catch (Exception e) {
            log.warn("[RecoveryService] PG 조회 실패 - orderNo: {} → CANCELLED 처리", managedOrder.getOrderNo());
            managedOrder.markAsCancelled();
            paymentLogService.saveLog(managedOrder.getOrderNo(), PaymentLog.LogType.SCHEDULER_RECOVERY,
                    null, null, false, "PG 조회 실패: " + e.getMessage());
            return false;
        }

        if (pgPayment.isPaid()) {
            if (paymentRepository.existsByImpUid(pgPayment.getPaymentKey())) {
                log.info("[RecoveryService] 이미 처리된 결제 스킵 - orderNo: {}", managedOrder.getOrderNo());
                return false;
            }
            Payment payment = Payment.builder()
                    .orderId(managedOrder.getId())
                    .impUid(pgPayment.getPaymentKey())
                    .merchantUid(managedOrder.getOrderNo())
                    .payMethod(pgPayment.getPayMethod())
                    .paidAmount(pgPayment.getAmount())
                    .pgProvider(managedOrder.getPgProvider())
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
                    null, pgPayment, false, null);
            log.info("[RecoveryService] CANCELLED 처리 - orderNo: {}", managedOrder.getOrderNo());
            return false;
        }
    }

    /**
     * 가상계좌 Webhook 누락 복구
     *
     * PENDING_PAYMENT 주문에 대해 PG API로 입금 여부를 직접 조회.
     * paid 상태면 VirtualAccountService.processDeposit 위임.
     *
     * [주의] VirtualAccountService를 파라미터로 받는 이유:
     *   순환 참조 방지 (VirtualAccountService → PaymentService, PaymentRecoveryService → VirtualAccountService)
     *   스케줄러에서 직접 주입받아 전달하는 방식으로 해결.
     */
    @Transactional
    public void recoverVirtualAccountWithTx(Order order, VirtualAccountService virtualAccountService) {
        Order managedOrder = orderRepository.findByOrderNo(order.getOrderNo())
                .orElseThrow(() -> new IllegalStateException("복구 대상 주문 미조회: " + order.getOrderNo()));

        // 이미 처리된 경우 스킵
        if (managedOrder.isPaid()) {
            log.debug("[RecoveryService] 가상계좌 이미 결제 완료 스킵 - orderNo: {}", managedOrder.getOrderNo());
            return;
        }

        // 가상계좌 정보 조회
        var vaOpt = virtualAccountRepository.findByOrderNo(managedOrder.getOrderNo());
        if (vaOpt.isEmpty()) {
            log.warn("[RecoveryService] 가상계좌 정보 없음 - orderNo: {}", managedOrder.getOrderNo());
            return;
        }

        var va = vaOpt.get();
        if (!va.isIssued()) {
            log.debug("[RecoveryService] 가상계좌 ISSUED 상태 아님 스킵 - orderNo: {}, status: {}",
                    managedOrder.getOrderNo(), va.getStatus());
            return;
        }

        // PG API로 실제 입금 여부 조회
        PgPaymentDetail pgPayment;
        try {
            pgPayment = pgRouter.route(managedOrder.getPgProvider())
                    .getPaymentByPaymentKey(va.getImpUid());
        } catch (Exception e) {
            log.warn("[RecoveryService] 가상계좌 PG 조회 실패 - orderNo: {}", managedOrder.getOrderNo(), e);
            return;
        }

        if (pgPayment.isPaid()) {
            log.info("[RecoveryService] 가상계좌 입금 확인(복구) - orderNo: {}", managedOrder.getOrderNo());
            virtualAccountService.processDeposit(va.getImpUid());
            paymentLogService.saveLog(managedOrder.getOrderNo(), PaymentLog.LogType.SCHEDULER_RECOVERY,
                    null, pgPayment, true, "가상계좌 입금 복구");
        } else {
            log.debug("[RecoveryService] 가상계좌 미입금 - orderNo: {}, PG상태: {}",
                    managedOrder.getOrderNo(), pgPayment.getStatus());
        }
    }
}
