package com.paycore.saga.scheduler;

import com.paycore.saga.domain.SagaDeadLetter;
import com.paycore.saga.service.SagaDeadLetterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Saga DLQ 재시도 스케줄러
 *
 * PENDING 상태의 Dead Letter를 1분마다 배치(100건) 재시도.
 * retry() 실행 후 status가 변경되므로 항상 page=0으로 조회한다.
 * page를 증가시키면 처리된 건이 결과에서 빠져 다음 건을 스킵하는 버그가 발생한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeadLetterRetryScheduler {

    private final SagaDeadLetterService sagaDeadLetterService;
    private final RedissonClient redissonClient;

    private static final String RETRY_LOCK_KEY = "lock:scheduler:dlq-retry";
    private static final int BATCH_SIZE = 100;

    @Scheduled(fixedDelay = 60_000)
    public void retryDeadLetters() {
        RLock lock = redissonClient.getLock(RETRY_LOCK_KEY);
        boolean acquired;
        try {
            acquired = lock.tryLock(0L, 55L, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        if (!acquired) {
            log.debug("[DLQ Scheduler] 다른 인스턴스 실행 중 - 스킵");
            return;
        }

        try {
            List<SagaDeadLetter> pending;
            int totalProcessed = 0;

            do {
                pending = sagaDeadLetterService.findPending(PageRequest.of(0, BATCH_SIZE));
                if (pending.isEmpty()) break;

                log.info("[DLQ Scheduler] 재시도 배치 - {}건", pending.size());
                pending.forEach(sagaDeadLetterService::retry);
                totalProcessed += pending.size();
            } while (pending.size() == BATCH_SIZE);

            if (totalProcessed > 0) {
                log.info("[DLQ Scheduler] 재시도 완료 - 총 {}건", totalProcessed);
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
