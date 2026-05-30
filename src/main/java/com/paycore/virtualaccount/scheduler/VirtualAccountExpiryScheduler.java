package com.paycore.virtualaccount.scheduler;

import com.paycore.virtualaccount.domain.VirtualAccount;
import com.paycore.virtualaccount.domain.VirtualAccountStatus;
import com.paycore.virtualaccount.repository.VirtualAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.orm.jpa.JpaOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 가상계좌 만료 처리 스케줄러
 *
 * [실행 주기] 10분 (fixedDelay 기준)
 *   - 가상계좌 만료는 분 단위 정확도로 충분. 짧게 해도 실익 없음.
 *   - 만료 즉시 처리가 필요하면 dueDate 알람 큐(SQS/Kafka) 방식으로 변경.
 *
 * [분산 환경 중복 실행 방지]
 * Redisson tryLock(waitTime=0, leaseTime=9분) 사용.
 * leaseTime < 주기(10분)으로 다음 실행 전 락 해제 보장.
 *
 * [처리 내용]
 * 1. ISSUED 상태 + dueDate < now 인 VirtualAccount 목록 조회
 * 2. VirtualAccount.status → EXPIRED
 * 3. 연결된 Order.status → CANCELLED
 *
 * [낙관적 락 충돌 처리]
 *   VirtualAccount에 @Version이 적용됨.
 *   만료 스케줄러가 EXPIRED 처리 중 입금 Webhook이 DEPOSITED로 변경하면
 *   스케줄러의 TX 커밋 시 JpaOptimisticLockingFailureException 발생.
 *   → 이 충돌은 "입금이 먼저 처리된 정상 케이스" → 에러가 아닌 INFO 로그로 처리.
 *
 * [EXPIRED 이후 재발급 여부]
 *   → EXPIRED 상태는 재발급 불가. 새 주문 생성 후 VA 발급 필요.
 *   → 이유: 만료 스케줄러가 Order.markAsCancelled()를 함께 처리하므로
 *      CANCELLED 주문의 상태 역전환은 결제 이력 무결성을 훼손함.
 *
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VirtualAccountExpiryScheduler {

    private final VirtualAccountRepository virtualAccountRepository;
    private final VirtualAccountExpiryProcessor expiryProcessor;
    private final RedissonClient redissonClient;

    private static final String LOCK_KEY = "lock:scheduler:va-expiry";

    @Scheduled(fixedDelay = 600_000) // 10분
    public void expireVirtualAccounts() {
        RLock lock = redissonClient.getLock(LOCK_KEY);

        boolean acquired;
        try {
            acquired = lock.tryLock(0L, 9L, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[VA-Expiry-Scheduler] 락 획득 인터럽트 - 스킵");
            return;
        }

        if (!acquired) {
            log.debug("[VA-Expiry-Scheduler] 다른 인스턴스 실행 중 - 스킵");
            return;
        }

        try {
            doExpire();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void doExpire() {
        List<VirtualAccount> expiredList = virtualAccountRepository
                .findByStatusAndDueDateBefore(VirtualAccountStatus.ISSUED, LocalDateTime.now());

        if (expiredList.isEmpty()) {
            log.debug("[VA-Expiry-Scheduler] 만료 대상 없음");
            return;
        }

        log.info("[VA-Expiry-Scheduler] 가상계좌 만료 처리 시작 - 대상: {}건", expiredList.size());

        int success = 0, skipped = 0, fail = 0;
        for (VirtualAccount va : expiredList) {
            try {
                expiryProcessor.expire(va.getId());
                success++;
            } catch (JpaOptimisticLockingFailureException | jakarta.persistence.OptimisticLockException e) {
                // 낙관적 락 충돌 = 입금 Webhook이 먼저 DEPOSITED 처리 → 정상 케이스
                skipped++;
                log.info("[VA-Expiry-Scheduler] 낙관적 락 충돌 - 입금이 먼저 처리된 것으로 판단, 스킵 - vaId: {}, merchantOrderId: {}",
                        va.getId(), va.getMerchantOrderId());
            } catch (Exception e) {
                fail++;
                log.error("[VA-Expiry-Scheduler] 만료 처리 실패 - vaId: {}, merchantOrderId: {} (수동 처리 필요)",
                        va.getId(), va.getMerchantOrderId(), e);
            }
        }

        log.info("[VA-Expiry-Scheduler] 만료 처리 완료 - 성공: {}건, 입금충돌스킵: {}건, 실패: {}건",
                success, skipped, fail);
    }
}
