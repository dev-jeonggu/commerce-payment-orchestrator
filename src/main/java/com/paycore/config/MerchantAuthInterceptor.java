package com.paycore.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paycore.common.exception.PaycoreException;
import com.paycore.merchant.domain.MerchantInfo;
import com.paycore.merchant.service.MerchantService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;

/**
 * 가맹점 API Key 인증 인터셉터
 *
 * 요청 헤더:
 *   X-Merchant-Id : 가맹점 ID (CPID)
 *   X-Api-Key     : 가맹점 secretKey (등록 시 발급)
 *
 * 인증 성공 시 request attribute "authenticatedMerchantId"에 merchantId 저장.
 * 컨트롤러에서 이 attribute를 신뢰하여 사용.
 *
 * 제외 경로: /api/v1/payments/webhook/**, /api/v1/merchants/** (별도 인증)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MerchantAuthInterceptor implements HandlerInterceptor {

    public static final String AUTHENTICATED_MERCHANT_ID = "authenticatedMerchantId";

    private final MerchantService merchantService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        String merchantId = request.getHeader("X-Merchant-Id");
        String apiKey = request.getHeader("X-Api-Key");

        if (merchantId == null || merchantId.isBlank() || apiKey == null || apiKey.isBlank()) {
            writeUnauthorized(response, "X-Merchant-Id 와 X-Api-Key 헤더가 필요합니다.");
            return false;
        }

        // getMerchantInfoCached: Redis 캐시 경유 (TTL 5분). 미존재 시 PaycoreException.
        MerchantInfo merchantInfo;
        try {
            merchantInfo = merchantService.getMerchantInfoCached(merchantId);
        } catch (PaycoreException e) {
            log.warn("[MerchantAuth] 존재하지 않는 가맹점 - merchantId: {}", merchantId);
            writeUnauthorized(response, "인증에 실패했습니다.");
            return false;
        }

        if (!merchantInfo.getSecretKey().equals(apiKey)) {
            log.warn("[MerchantAuth] API Key 불일치 - merchantId: {}", merchantId);
            writeUnauthorized(response, "인증에 실패했습니다.");
            return false;
        }

        if (merchantInfo.isSuspended()) {
            log.warn("[MerchantAuth] 정지된 가맹점 요청 - merchantId: {}", merchantId);
            writeUnauthorized(response, "정지된 가맹점입니다. 고객센터에 문의하세요.");
            return false;
        }

        request.setAttribute(AUTHENTICATED_MERCHANT_ID, merchantId);
        return true;
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                objectMapper.writeValueAsString(Map.of("success", false, "message", message)));
    }
}
