package com.paycore.lock;

import com.paycore.common.exception.ErrorCode;
import com.paycore.common.exception.PaycoreException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Redis 분산락 서비스
 *
 * [면접 포인트] 중복 결제 방지 (멱등성 보장)
 * 동시에 동일한 주문에 대한 결제 요청이 들어왔을 때
 * 하나만 처리하고 나머지는 거부
 *
 * tryLock(waitTime=5초, leaseTime=10초):
 * - waitTime: 락 획득을 최대 5초 대기
 * - leaseTime: 락 보유 최대 10초 (서버 장애 시 자동 해제)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DistributedLockService {

    private final RedissonClient redissonClient;

    private static final String PAYMENT_LOCK_PREFIX = "lock:payment:";
    private static final long WAIT_TIME = 5L;
    private static final long LEASE_TIME = 10L;

    /**
     * 결제 검증용 분산락 실행
     * 같은 주문에 대한 동시 요청을 직렬화
     */
    public <T> T executeWithPaymentLock(String merchantUid, LockCallback<T> callback) {
        String lockKey = PAYMENT_LOCK_PREFIX + merchantUid;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            boolean acquired = lock.tryLock(WAIT_TIME, LEASE_TIME, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("[DistributedLock] 락 획득 실패 - key: {}", lockKey);
                throw new PaycoreException(ErrorCode.LOCK_ACQUISITION_FAILED);
            }

            log.debug("[DistributedLock] 락 획득 - key: {}", lockKey);
            return callback.execute();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PaycoreException(ErrorCode.LOCK_ACQUISITION_FAILED);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("[DistributedLock] 락 해제 - key: {}", lockKey);
            }
        }
    }

    @FunctionalInterface
    public interface LockCallback<T> {
        T execute();
    }
}
