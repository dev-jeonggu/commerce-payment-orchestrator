package com.paycore.payment.client;

import com.paycore.common.exception.ErrorCode;
import com.paycore.common.exception.PaycoreException;
import com.paycore.payment.client.dto.PortOneTokenResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * PortOne 액세스 토큰 Redis 캐시 관리자
 *
 * [문제] 기존 코드는 getPayment, cancelPayment 등 모든 API 호출 전에 매번 토큰을 새로 발급함
 *        → 결제 1건당 PG API 2회 (토큰 발급 + 실제 호출) 낭비
 *        → 초당 10만 건 환경에서 PG 토큰 발급 API가 병목이 되고, PortOne 레이트 리밋 도달 위험
 *
 * [해결] Redis RBucket에 토큰 캐시 (TTL=25분, 토큰 유효기간=30분)
 *        Redisson 분산락으로 동시 토큰 갱신 요청 직렬화 (Thunder Herd 방지)
 *
 * [논란 포인트] leaseTime=25분짜리 락이 너무 긴 것 아닌가?
 *   → 락은 토큰 발급 시간(수백ms)만 유지됨. leaseTime은 서버 crash 시 자동 해제 보장.
 *   → 실제 waitTime=3초, leaseTime=5초로 충분히 짧게 설정.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortOneTokenManager {

    private final RedissonClient redissonClient;
    private final WebClient portoneWebClient;

    @Value("${portone.imp-key}")
    private String impKey;

    @Value("${portone.imp-secret}")
    private String impSecret;

    private static final String TOKEN_BUCKET_KEY = "portone:access_token";
    private static final String TOKEN_REFRESH_LOCK = "lock:portone:token_refresh";
    private static final Duration TOKEN_TTL = Duration.ofMinutes(25);

    /**
     * 토큰 조회 (캐시 미스 시 발급)
     *
     * 동시성: RLock으로 단 하나의 스레드만 실제 발급, 나머지는 대기 후 캐시 사용 (Double-Checked Locking)
     */
    public String getToken() {
        RBucket<String> bucket = redissonClient.getBucket(TOKEN_BUCKET_KEY);
        String cached = bucket.get();
        if (cached != null) {
            return cached;
        }

        RLock refreshLock = redissonClient.getLock(TOKEN_REFRESH_LOCK);
        try {
            boolean acquired = refreshLock.tryLock(3L, 5L, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("[PortOneTokenManager] 토큰 갱신 락 획득 실패 - 재시도 권장");
                throw new PaycoreException(ErrorCode.PG_AUTHENTICATION_FAILED,
                        "토큰 갱신 중 충돌. 잠시 후 재시도해주세요.");
            }

            try {
                // Double-Checked: 락 획득 후 다른 스레드가 이미 갱신했을 수 있음
                cached = bucket.get();
                if (cached != null) {
                    return cached;
                }

                String newToken = fetchNewToken();
                bucket.set(newToken, TOKEN_TTL);
                log.info("[PortOneTokenManager] 토큰 갱신 완료 (TTL={}분)", TOKEN_TTL.toMinutes());
                return newToken;
            } finally {
                refreshLock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PaycoreException(ErrorCode.PG_AUTHENTICATION_FAILED, "토큰 발급 인터럽트");
        }
    }

    /**
     * 토큰 강제 만료 (401 응답 수신 시 캐시 무효화 후 재발급)
     *
     * [논란 포인트] 토큰 만료 전에 PortOne이 401을 내려보낼 수 있는가?
     *   → 서버 재배포, 비밀키 변경 등으로 강제 만료될 수 있음.
     *   → 호출부에서 401 수신 시 evictToken() 호출 후 1회 재시도 패턴 권장.
     */
    public void evictToken() {
        redissonClient.getBucket(TOKEN_BUCKET_KEY).delete();
        log.info("[PortOneTokenManager] 토큰 캐시 강제 만료");
    }

    private String fetchNewToken() {
        log.debug("[PortOneTokenManager] PortOne 토큰 발급 요청");
        PortOneTokenResponse response = portoneWebClient.post()
                .uri("/users/getToken")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("imp_key", impKey, "imp_secret", impSecret))
                .retrieve()
                .bodyToMono(PortOneTokenResponse.class)
                .block();

        if (response == null || !response.isSuccess()) {
            throw new PaycoreException(ErrorCode.PG_AUTHENTICATION_FAILED,
                    "PortOne 토큰 발급 실패");
        }
        return response.getAccessToken();
    }
}
