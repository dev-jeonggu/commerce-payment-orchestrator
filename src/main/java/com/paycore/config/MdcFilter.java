package com.paycore.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 요청별 MDC 컨텍스트 주입 필터
 *
 * Kibana에서 단일 요청의 전체 로그를 추적하기 위해 필요한 필드를 MDC에 주입한다.
 * LogstashEncoder가 MDC 필드를 JSON 최상위 필드로 자동 포함하므로 별도 처리 불필요.
 *
 * 주입 필드:
 *   - requestId : 요청당 고유 UUID (없으면 자동 생성). Kibana에서 단일 HTTP 요청 추적.
 *   - merchantId : X-Merchant-Id 헤더. 가맹점별 로그 필터링.
 *
 * traceId / spanId 는 Micrometer Tracing이 자동으로 MDC에 주입하므로 여기서 설정하지 않는다.
 */
@Component
@Order(1)
public class MdcFilter extends OncePerRequestFilter {

    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String MERCHANT_ID_HEADER = "X-Merchant-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String requestId = request.getHeader(REQUEST_ID_HEADER);
            if (requestId == null || requestId.isBlank()) {
                requestId = UUID.randomUUID().toString();
            }

            String merchantId = request.getHeader(MERCHANT_ID_HEADER);

            MDC.put("requestId", requestId);
            if (merchantId != null && !merchantId.isBlank()) {
                MDC.put("merchantId", merchantId);
            }

            // 응답 헤더에 requestId 포함 — 가맹점이 문의 시 이 값으로 로그 조회 가능
            response.setHeader(REQUEST_ID_HEADER, requestId);

            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("requestId");
            MDC.remove("merchantId");
        }
    }
}
