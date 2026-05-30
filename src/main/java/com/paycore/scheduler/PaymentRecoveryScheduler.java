package com.paycore.scheduler;

import com.paycore.virtualaccount.domain.VirtualAccount;
import com.paycore.virtualaccount.domain.VirtualAccountStatus;
import com.paycore.virtualaccount.repository.VirtualAccountRepository;
import com.paycore.virtualaccount.service.VirtualAccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 가상계좌 입금 대기 복구 스케줄러
 *
 * ISSUED 상태 가상계좌에 대해 Webhook 누락 시 직접 조회하여 복구.
 * Redisson tryLock(waitTime=0): 락 획득 실패 시 즉시 스킵 (다른 인스턴스 실행 중).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentRecoveryScheduler {

    private final PaymentRecoveryService paymentRecoveryService;
    private final VirtualAccountRepository virtualAccountRepository;
    private final VirtualAccountService virtualAccountService;
    private final RedissonClient redissonClient;

    private static final String SCHEDULER_LOCK_KEY = "lock:scheduler:pending-recovery";

    @Scheduled(fixedDelayString = "${scheduler.pending-recovery.fixed-delay:300000}")
    public void recoverPendingPayments() {
        RLock schedulerLock = redissonClient.getLock(SCHEDULER_LOCK_KEY);

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
        // 가상계좌 입금 대기 Webhook 누락 복구
        recoverPendingVirtualAccounts();
    }

    private void recoverPendingVirtualAccounts() {
        // ISSUED 상태이면서 만료 기한이 지나지 않은 가상계좌 대상
        List<VirtualAccount> issuedVas = virtualAccountRepository
                .findByStatusAndDueDateBefore(VirtualAccountStatus.ISSUED, LocalDateTime.now().plusYears(1));

        if (issuedVas.isEmpty()) {
            log.debug("[Scheduler] 가상계좌 입금 대기 복구 대상 없음");
            return;
        }

        log.info("[Scheduler] 가상계좌 입금 대기 복구 시작 - 대상: {}건", issuedVas.size());

        for (VirtualAccount va : issuedVas) {
            try {
                paymentRecoveryService.recoverVirtualAccountWithTx(va, virtualAccountService);
            } catch (Exception e) {
                log.error("[Scheduler] 가상계좌 복구 실패 - merchantOrderId: {} (수동 처리 필요)",
                        va.getMerchantOrderId(), e);
            }
        }

        log.info("[Scheduler] 가상계좌 입금 대기 복구 완료");
    }
}
