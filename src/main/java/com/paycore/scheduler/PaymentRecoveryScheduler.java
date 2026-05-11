package com.paycore.scheduler;

import com.paycore.order.domain.Order;
import com.paycore.order.domain.OrderStatus;
import com.paycore.order.repository.OrderRepository;
import com.paycore.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * PENDING 주문 자동 복구 스케줄러
 *
 * 발생 케이스:
 * 1. 사용자가 결제 후 브라우저 종료 → verify API 미호출
 * 2. Webhook 유실
 * 3. 서버 장애로 verify 처리 실패
 *
 * [트랜잭션 흐름]
 * 1. recoverOrderWithTx (PaymentRecoveryService, @Transactional)
 *    → Payment 저장 + Order PAID 원자적 커밋
 * 2. processAfterPayment (PaymentService, @Transactional)
 *    → 커밋된 Payment 조회 후 재고/포인트 후처리
 *    → 실패 시 Saga(REQUIRES_NEW)가 커밋된 Payment를 정상 조회하여 보상 취소
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentRecoveryScheduler {

    private final OrderRepository orderRepository;
    private final PaymentRecoveryService paymentRecoveryService;
    private final PaymentService paymentService;

    @Value("${scheduler.pending-recovery.pending-threshold-minutes:30}")
    private int pendingThresholdMinutes;

    @Scheduled(fixedDelayString = "${scheduler.pending-recovery.fixed-delay:300000}")
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
                boolean recovered = paymentRecoveryService.recoverOrderWithTx(order);
                if (recovered) {
                    try {
                        paymentService.processAfterPayment(order.getOrderNo());
                    } catch (Exception e) {
                        log.error("[Scheduler] 복구 후처리 실패 (Saga 보상 취소 완료) - orderNo: {}",
                                order.getOrderNo());
                    }
                }
            } catch (Exception e) {
                log.error("[Scheduler] 주문 복구 실패 - orderNo: {} (수동 처리 필요)",
                        order.getOrderNo(), e);
            }
        }

        log.info("[Scheduler] PENDING 주문 복구 완료");
    }
}
