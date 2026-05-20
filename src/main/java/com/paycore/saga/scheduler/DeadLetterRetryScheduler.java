package com.paycore.saga.scheduler;

import com.paycore.saga.domain.SagaDeadLetter;
import com.paycore.saga.service.SagaDeadLetterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * DLQ 재시도 스케줄러
 *
 * PENDING 상태의 Dead Letter를 1분마다 재시도.
 * 분산 환경에서 Redisson Lock으로 중복 실행 방지.
 *
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeadLetterRetryScheduler {

    private final SagaDeadLetterService sagaDeadLetterService;
    private final RedissonClient redissonClient;

    private static final String RETRY_LOCK_KEY = "lock:scheduler:dlq-retry";

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
            List<SagaDeadLetter> pending = sagaDeadLetterService.findPending();
            if (pending.isEmpty()) {
                return;
            }
            log.info("[DLQ Scheduler] 재시도 시작 - {}건", pending.size());
            pending.forEach(sagaDeadLetterService::retry);
            log.info("[DLQ Scheduler] 재시도 완료");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
