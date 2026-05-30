package com.paycore.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 멱등성 키 필터
 *
 * POST 요청에 X-Idempotency-Key 헤더가 있으면:
 *   1. Redis에 캐시된 응답이 있으면 → 그대로 반환 (DB 조회/결제 재처리 없음)
 *   2. 처리 중인 요청이면 → 409 Conflict 반환
 *   3. 최초 요청이면 → 처리 후 응답을 24시간 캐시
 *
 * 헤더가 없으면 필터를 통과 (하위 호환 유지, 선택적 사용).
 * 적용 경로: /api/v1/payments, /api/v1/virtual-accounts, /api/v1/billing-keys
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyFilter extends OncePerRequestFilter {

    private final StringRedisTemplate redisTemplate;

    private static final String CACHE_PREFIX  = "idempotency:response:";
    private static final String LOCK_PREFIX   = "idempotency:lock:";
    private static final Duration CACHE_TTL   = Duration.ofHours(24);
    private static final Duration LOCK_TTL    = Duration.ofSeconds(30);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) return true;
        String path = request.getRequestURI();
        return !(path.startsWith("/api/v1/payments")
                || path.startsWith("/api/v1/virtual-accounts")
                || path.startsWith("/api/v1/billing-keys"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String idempotencyKey = request.getHeader("X-Idempotency-Key");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        // merchantId로 네임스페이스 분리: 서로 다른 가맹점이 동일 키를 사용해도 충돌 없음
        String merchantId = request.getHeader("X-Merchant-Id");
        String namespace = (merchantId != null && !merchantId.isBlank()) ? merchantId : "_global";

        String cacheKey = CACHE_PREFIX + namespace + ":" + idempotencyKey;
        String lockKey  = LOCK_PREFIX  + namespace + ":" + idempotencyKey;

        // 이미 처리 완료된 요청 → 캐시 응답 반환
        String cachedResponse = redisTemplate.opsForValue().get(cacheKey);
        if (cachedResponse != null) {
            log.debug("[Idempotency] 캐시 응답 반환 - key: {}", idempotencyKey);
            writeJson(response, HttpServletResponse.SC_OK, cachedResponse);
            return;
        }

        // 처리 중인 요청 → 409 반환
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey, "processing", LOCK_TTL);
        if (!Boolean.TRUE.equals(locked)) {
            log.warn("[Idempotency] 동일 키 처리 중 - key: {}", idempotencyKey);
            writeJson(response, HttpServletResponse.SC_CONFLICT,
                    "{\"success\":false,\"message\":\"동일한 요청이 처리 중입니다. 잠시 후 다시 시도하거나 동일 키로 재조회하세요.\"}");
            return;
        }

        // 최초 요청 → 처리 후 응답 캐시
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);
        try {
            filterChain.doFilter(request, responseWrapper);
        } finally {
            String responseBody = new String(responseWrapper.getContentAsByteArray(), StandardCharsets.UTF_8);
            int status = responseWrapper.getStatus();
            // 2xx 성공 응답만 캐시. 4xx(잘못된 요청)를 캐시하면 수정 후 재시도해도
            // 동일 키로 캐시된 에러 응답이 반환되어 24시간 동안 결제 불가 상태가 된다.
            if (status >= 200 && status < 300) {
                redisTemplate.opsForValue().set(cacheKey, responseBody, CACHE_TTL);
                log.debug("[Idempotency] 응답 캐시 저장 - key: {}, status: {}", idempotencyKey, status);
            }
            redisTemplate.delete(lockKey);
            responseWrapper.copyBodyToResponse();
        }
    }

    private void writeJson(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(body);
    }
}
