package com.paycore.webhook.scheduler;

import com.paycore.webhook.domain.WebhookDeadLetter;
import com.paycore.webhook.service.WebhookRetryService;
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
 * Webhook DLQ 재시도 스케줄러
 *
 * PENDING 상태 Dead Letter를 1분마다 재시도.
 * Redisson 분산 락으로 다중 인스턴스 환경에서 중복 실행 방지.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookRetryScheduler {

    private final WebhookRetryService webhookRetryService;
    private final RedissonClient redissonClient;

    private static final String LOCK_KEY = "lock:scheduler:webhook-retry";

    @Scheduled(fixedDelay = 60_000)
    public void retryFailedWebhooks() {
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean acquired;
        try {
            acquired = lock.tryLock(0L, 55L, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        if (!acquired) {
            log.debug("[WebhookRetryScheduler] 다른 인스턴스 실행 중 - 스킵");
            return;
        }

        try {
            final int batchSize = 100;
            List<WebhookDeadLetter> pending;
            int totalProcessed = 0;

            // retry() 실행 후 status가 PENDING→PROCESSING/RESOLVED로 변경되므로
            // 항상 page=0으로 조회해야 처리된 건이 빠지고 다음 건이 올라온다.
            // page를 증가시키면 status 변경으로 인한 offset 이동으로 항목을 스킵하게 된다.
            do {
                pending = webhookRetryService.findPending(PageRequest.of(0, batchSize));
                if (pending.isEmpty()) break;

                log.info("[WebhookRetryScheduler] 재시도 배치 - {}건", pending.size());
                pending.forEach(webhookRetryService::retry);
                totalProcessed += pending.size();
            } while (pending.size() == batchSize);

            if (totalProcessed > 0) {
                log.info("[WebhookRetryScheduler] 재시도 완료 - 총 {}건", totalProcessed);
            }
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }
}
