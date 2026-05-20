package com.paycore.scheduler;

import com.paycore.order.domain.Order;
import com.paycore.order.domain.OrderStatus;
import com.paycore.order.repository.OrderRepository;
import com.paycore.payment.service.PaymentService;
import com.paycore.virtualaccount.service.VirtualAccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * PENDING 주문 자동 복구 스케줄러
 *
 * [Step 2 변경] 분산 환경 중복 실행 방지
 * Redisson tryLock(waitTime=0): 락 획득 실패 시 즉시 스킵 (다른 인스턴스가 실행 중)
 *
 * Redisson이 이미 사용 중이므로 추가 의존성 없이 동일한 분산락 효과를 제공.
 * leaseTime=4분: 스케줄러 주기(5분)보다 짧게 설정하여 다음 실행에서 락 획득 가능.
 * 스케줄러 실행 시간이 4분을 초과하는 경우 모니터링 필요.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentRecoveryScheduler {

    private final OrderRepository orderRepository;
    private final PaymentRecoveryService paymentRecoveryService;
    private final PaymentService paymentService;
    private final VirtualAccountService virtualAccountService;
    private final RedissonClient redissonClient;

    private static final String SCHEDULER_LOCK_KEY = "lock:scheduler:pending-recovery";

    @Value("${scheduler.pending-recovery.pending-threshold-minutes:30}")
    private int pendingThresholdMinutes;

    @Scheduled(fixedDelayString = "${scheduler.pending-recovery.fixed-delay:300000}")
    public void recoverPendingOrders() {
        RLock schedulerLock = redissonClient.getLock(SCHEDULER_LOCK_KEY);

        // waitTime=0: 락 획득 실패 시 즉시 리턴 (다른 인스턴스가 실행 중)
        boolean acquired;
        try {
            acquired = schedulerLock.tryLock(0L, 4L, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[Scheduler] 락 획득 인터럽트 - 스킵");
            return;
        }

        if (!acquired) {
            log.debug("[Scheduler] 다른 인스턴스 실행 중 - 스킵");
            return;
        }

        try {
            doRecover();
        } finally {
            if (schedulerLock.isHeldByCurrentThread()) {
                schedulerLock.unlock();
            }
        }
    }

    private void doRecover() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(pendingThresholdMinutes);

        // 1. 일반 결제 PENDING 주문 복구 (카드/계좌이체 미완료 건)
        recoverPendingOrders(threshold);

        // 2. 가상계좌 입금 대기 PENDING_PAYMENT 주문 Webhook 누락 복구
        recoverPendingPaymentOrders(threshold);
    }

    private void recoverPendingOrders(LocalDateTime threshold) {
        List<Order> pendingOrders = orderRepository.findByStatusAndCreatedAtBefore(
                OrderStatus.PENDING, threshold);

        if (pendingOrders.isEmpty()) {
            log.debug("[Scheduler] PENDING 복구 대상 없음");
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
                        log.error("[Scheduler] 복구 후처리 실패 (Saga 보상 완료) - orderNo: {}",
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

    /**
     * 가상계좌 Webhook 누락 복구
     *
     * PENDING_PAYMENT 상태: 가상계좌 발급 완료 → 입금 대기 중.
     * PG Webhook 누락 시 PG API로 입금 여부를 직접 조회.
     *
     * dueDate 만료 건은 VirtualAccountExpiryScheduler가 EXPIRED 처리.
     * 여기서는 dueDate 체크 없이 PG 조회 후 상태 반영.
     * PG가 "paid"를 반환하면 입금 처리, "cancelled/expired"면 취소 처리.
     */
    private void recoverPendingPaymentOrders(LocalDateTime threshold) {
        List<Order> pendingPaymentOrders = orderRepository.findPendingPaymentOrdersBefore(
                OrderStatus.PENDING_PAYMENT, threshold);

        if (pendingPaymentOrders.isEmpty()) {
            log.debug("[Scheduler] PENDING_PAYMENT(가상계좌) 복구 대상 없음");
            return;
        }

        log.info("[Scheduler] 가상계좌 입금 대기 복구 시작 - 대상: {}건", pendingPaymentOrders.size());

        for (Order order : pendingPaymentOrders) {
            try {
                paymentRecoveryService.recoverVirtualAccountWithTx(order, virtualAccountService);
            } catch (Exception e) {
                log.error("[Scheduler] 가상계좌 복구 실패 - orderNo: {} (수동 처리 필요)",
                        order.getOrderNo(), e);
            }
        }

        log.info("[Scheduler] 가상계좌 입금 대기 복구 완료");
    }
}
