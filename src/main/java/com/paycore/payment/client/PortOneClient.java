package com.paycore.payment.client;

import com.paycore.common.exception.ErrorCode;
import com.paycore.common.exception.PaycoreException;
import com.paycore.payment.client.dto.PortOneCancelRequest;
import com.paycore.payment.client.dto.PortOnePaymentResponse;
import com.paycore.payment.client.dto.PortOneTokenResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

/**
 * PortOne(구 아이포트) V1 API 클라이언트
 * WebClient를 활용한 논블로킹 HTTP 통신
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortOneClient {

    private final WebClient portoneWebClient;

    @Value("${portone.imp-key}")
    private String impKey;

    @Value("${portone.imp-secret}")
    private String impSecret;

    /**
     * PortOne 인증 토큰 발급
     */
    public String getAccessToken() {
        log.debug("[PortOneClient] 인증 토큰 요청");
        PortOneTokenResponse response = portoneWebClient.post()
                .uri("/users/getToken")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("imp_key", impKey, "imp_secret", impSecret))
                .retrieve()
                .bodyToMono(PortOneTokenResponse.class)
                .block();

        if (response == null || !response.isSuccess()) {
            log.error("[PortOneClient] 인증 토큰 발급 실패");
            throw new PaycoreException(ErrorCode.PG_AUTHENTICATION_FAILED);
        }

        return response.getAccessToken();
    }

    /**
     * imp_uid로 결제 단건 조회
     * Webhook 및 결제 검증에서 반드시 단건 조회 후 상태를 신뢰해야 함
     */
    public PortOnePaymentResponse getPaymentByImpUid(String impUid) {
        log.debug("[PortOneClient] 결제 단건 조회 - impUid: {}", impUid);
        String accessToken = getAccessToken();

        PortOnePaymentResponse response = portoneWebClient.get()
                .uri("/payments/{imp_uid}", impUid)
                .header("Authorization", accessToken)
                .retrieve()
                .bodyToMono(PortOnePaymentResponse.class)
                .block();

        if (response == null || !response.isSuccess()) {
            log.error("[PortOneClient] 결제 단건 조회 실패 - impUid: {}", impUid);
            throw new PaycoreException(ErrorCode.PG_API_ERROR, "결제 조회 실패: " + impUid);
        }

        return response;
    }

    /**
     * merchant_uid로 결제 단건 조회
     * 스케줄러 복구에서 사용 (imp_uid를 모를 때)
     */
    public PortOnePaymentResponse getPaymentByMerchantUid(String merchantUid) {
        log.debug("[PortOneClient] 결제 단건 조회 (merchant_uid) - merchantUid: {}", merchantUid);
        String accessToken = getAccessToken();

        PortOnePaymentResponse response = portoneWebClient.get()
                .uri("/payments/merchant_uid/{merchant_uid}", merchantUid)
                .header("Authorization", accessToken)
                .retrieve()
                .bodyToMono(PortOnePaymentResponse.class)
                .block();

        if (response == null || !response.isSuccess()) {
            log.error("[PortOneClient] 결제 단건 조회 실패 (merchant_uid) - merchantUid: {}", merchantUid);
            throw new PaycoreException(ErrorCode.PG_API_ERROR, "결제 조회 실패: " + merchantUid);
        }

        return response;
    }

    /**
     * 결제 취소 요청
     * amount가 null이면 전액 취소, 값이 있으면 부분 취소
     */
    public PortOnePaymentResponse cancelPayment(PortOneCancelRequest cancelRequest) {
        log.info("[PortOneClient] 결제 취소 요청 - merchantUid: {}, amount: {}",
                cancelRequest.getMerchantUid(), cancelRequest.getAmount());
        String accessToken = getAccessToken();

        PortOnePaymentResponse response = portoneWebClient.post()
                .uri("/payments/cancel")
                .header("Authorization", accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(cancelRequest)
                .retrieve()
                .bodyToMono(PortOnePaymentResponse.class)
                .block();

        if (response == null || !response.isSuccess()) {
            log.error("[PortOneClient] 결제 취소 실패 - merchantUid: {}", cancelRequest.getMerchantUid());
            throw new PaycoreException(ErrorCode.PAYMENT_CANCEL_FAILED);
        }

        return response;
    }
}
