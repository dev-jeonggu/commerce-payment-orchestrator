package com.paycore.scheduler;

import com.paycore.order.domain.Order;
import com.paycore.order.domain.OrderStatus;
import com.paycore.order.repository.OrderRepository;
import com.paycore.payment.client.PortOneClient;
import com.paycore.payment.client.dto.PortOnePaymentResponse;
import com.paycore.payment.domain.Payment;
import com.paycore.payment.domain.PaymentLog;
import com.paycore.payment.repository.PaymentRepository;
import com.paycore.payment.service.PaymentLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * PENDING 주문 자동 복구 스케줄러
 *
 * [면접 포인트] 장애 복구의 핵심
 *
 * 발생 케이스:
 * 1. 사용자가 결제 후 브라우저 종료 → verify API 미호출
 * 2. Webhook 유실
 * 3. 서버 장애로 verify 처리 실패
 *
 * 복구 로직:
 * - 5분 주기로 PENDING + 30분 경과 주문 조회
 * - PG 단건 조회로 실제 결제 상태 확인
 * - 결제 완료: PAID 처리
 * - 미결제: CANCELLED 처리
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentRecoveryScheduler {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PortOneClient portOneClient;
    private final PaymentLogService paymentLogService;

    @Value("${scheduler.pending-recovery.pending-threshold-minutes:30}")
    private int pendingThresholdMinutes;

    @Scheduled(fixedDelayString = "${scheduler.pending-recovery.fixed-delay:300000}")
    @Transactional
    public void recoverPendingOrders() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(pendingThresholdMinutes);
        List<Order> pendingOrders = orderRepository.findByStatusAndCreatedAtBefore(
                OrderStatus.PENDING, threshold);

        if (pendingOrders.isEmpty()) {
            log.debug("[Scheduler] 복구 대상 없음");
            return;
        }

        log.info("[Scheduler] PENDING 주문 복구 시작 - 대상: {}건", pendingOrders.size());

        for (Order order : pendingOrders) {
            try {
                recoverOrder(order);
            } catch (Exception e) {
                log.error("[Scheduler] 주문 복구 실패 - orderNo: {} (수동 처리 필요)",
                        order.getOrderNo(), e);
            }
        }

        log.info("[Scheduler] PENDING 주문 복구 완료");
    }

    private void recoverOrder(Order order) {
        log.info("[Scheduler] 복구 처리 - orderNo: {}, createdAt: {}",
                order.getOrderNo(), order.getCreatedAt());

        // PG 단건 조회 (imp_uid는 merchant_uid로 조회)
        // 실제로는 PG사에 merchant_uid 기반 조회 API를 사용
        PortOnePaymentResponse pgPayment;
        try {
            pgPayment = portOneClient.getPaymentByImpUid(order.getOrderNo());
        } catch (Exception e) {
            log.warn("[Scheduler] PG 조회 실패 - orderNo: {} → CANCELLED 처리", order.getOrderNo());
            order.markAsCancelled();
            paymentLogService.saveLog(order.getOrderNo(), PaymentLog.LogType.SCHEDULER_RECOVERY,
                    null, null, false, "PG 조회 실패: " + e.getMessage());
            return;
        }

        if (pgPayment.isPaid()) {
            // 결제 완료 → PAID 처리
            if (!paymentRepository.existsByImpUid(pgPayment.getResponse().getImpUid())) {
                Payment payment = Payment.builder()
                        .orderId(order.getId())
                        .impUid(pgPayment.getResponse().getImpUid())
                        .merchantUid(order.getOrderNo())
                        .payMethod(pgPayment.getResponse().getPayMethod())
                        .paidAmount(pgPayment.getAmount())
                        .build();
                paymentRepository.save(payment);
                order.markAsPaid();
                log.info("[Scheduler] PAID 복구 완료 - orderNo: {}", order.getOrderNo());
            }
        } else {
            // 미결제 → CANCELLED
            order.markAsCancelled();
            log.info("[Scheduler] CANCELLED 처리 - orderNo: {}", order.getOrderNo());
        }

        paymentLogService.saveLog(order.getOrderNo(), PaymentLog.LogType.SCHEDULER_RECOVERY,
                null, pgPayment, true, null);
    }
}
