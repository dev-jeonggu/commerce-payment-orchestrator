package com.paycore.payment.client;

import com.paycore.common.exception.ErrorCode;
import com.paycore.common.exception.PaycoreException;
import com.paycore.payment.client.dto.*;
import com.paycore.payment.pg.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * PortOne V1 API 클라이언트
 *
 * [변경 사항]
 * 1. PaymentGatewayClient 인터페이스 구현 → PgRouter로 라우팅 가능
 * 2. PortOneTokenManager 사용 → 토큰 Redis 캐싱 (매 호출마다 토큰 재발급 제거)
 * 3. 빌링키 결제, 가상계좌 발급 지원
 * 4. 401 응답 시 토큰 캐시 강제 만료 후 1회 재시도 (Token Refresh)
 *
 * Spring MVC(동기) 기반이므로 WebClient.block() 사용.
 * WebFlux 전환 시 block() 제거 후 reactive 체인으로 변경 필요.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortOneClient implements PaymentGatewayClient {

    private final WebClient portoneWebClient;
    private final PortOneTokenManager tokenManager;

    // ─── PaymentGatewayClient 구현 ────────────────────────────────────

    @Override
    public PgProvider provider() {
        return PgProvider.PORTONE;
    }

    @Override
    public PgPaymentDetail getPaymentByPaymentKey(String impUid) {
        log.debug("[PortOneClient] 결제 단건 조회 (imp_uid) - impUid: {}", impUid);
        PortOnePaymentResponse response = callWithTokenRefreshOnUnauthorized(
                () -> fetchPaymentByImpUid(impUid)
        );
        validateResponse(response, "결제 조회 실패: " + impUid);
        return toPaymentDetail(response);
    }

    @Override
    public PgPaymentDetail getPaymentByOrderId(String merchantUid) {
        log.debug("[PortOneClient] 결제 단건 조회 (merchant_uid) - merchantUid: {}", merchantUid);
        PortOnePaymentResponse response = callWithTokenRefreshOnUnauthorized(
                () -> fetchPaymentByMerchantUid(merchantUid)
        );
        validateResponse(response, "결제 조회 실패 (merchant_uid): " + merchantUid);
        return toPaymentDetail(response);
    }

    @Override
    public PgCancelResult cancel(PgCancelCommand command) {
        log.info("[PortOneClient] 결제 취소 요청 - orderId: {}, amount: {}",
                command.getOrderId(), command.getAmount());

        PortOneCancelRequest cancelRequest = PortOneCancelRequest.builder()
                .impUid(command.getPaymentKey())
                .merchantUid(command.getOrderId())
                .amount(command.getAmount())
                .reason(command.getReason())
                .build();

        PortOnePaymentResponse response = callWithTokenRefreshOnUnauthorized(
                () -> executeCancelRequest(cancelRequest)
        );
        validateResponse(response, "결제 취소 실패: " + command.getOrderId());

        PortOnePaymentResponse.PaymentData data = response.getResponse();
        long paid = data.getAmount() != null ? data.getAmount() : 0L;
        long cancelled = data.getCancelAmount() != null ? data.getCancelAmount() : 0L;
        return PgCancelResult.of(data.getImpUid(), cancelled, paid - cancelled);
    }

    @Override
    public PgBillingResult chargeBilling(PgBillingCommand command) {
        log.info("[PortOneClient] 빌링키 결제 - orderId: {}, amount: {}",
                command.getOrderId(), command.getAmount());

        PortOneSubscribePaymentRequest request = PortOneSubscribePaymentRequest.builder()
                .customerUid(command.getPgBillingKey())
                .merchantUid(command.getOrderId())
                .amount(command.getAmount())
                .name(command.getOrderName())
                .build();

        PortOnePaymentResponse response = callWithTokenRefreshOnUnauthorized(
                () -> executeSubscribePayment(request)
        );
        validateResponse(response, "빌링키 결제 실패: " + command.getOrderId());

        PortOnePaymentResponse.PaymentData data = response.getResponse();
        return PgBillingResult.builder()
                .paymentKey(data.getImpUid())
                .orderId(data.getMerchantUid())
                .amount(data.getAmount())
                .status(data.getStatus())
                .payMethod(data.getPayMethod())
                .build();
    }

    @Override
    public PgVirtualAccountResult issueVirtualAccount(PgVirtualAccountCommand command) {
        log.info("[PortOneClient] 가상계좌 발급 - orderId: {}, bankCode: {}",
                command.getOrderId(), command.getBankCode());

        PortOneVirtualAccountRequest request = PortOneVirtualAccountRequest.builder()
                .merchantUid(command.getOrderId())
                .amount(command.getAmount())
                .name(command.getOrderName())
                .vbankCode(command.getBankCode())
                .vbankHolder(command.getHolderName())
                .vbankDue(command.getDueDate() != null
                        ? command.getDueDate().atZone(ZoneId.systemDefault()).toEpochSecond()
                        : null)
                .build();

        PortOnePaymentResponse response = callWithTokenRefreshOnUnauthorized(
                () -> executeVirtualAccountRequest(request)
        );
        validateResponse(response, "가상계좌 발급 실패: " + command.getOrderId());

        PortOnePaymentResponse.PaymentData data = response.getResponse();
        LocalDateTime dueDate = data.getVbankDate() != null
                ? LocalDateTime.ofInstant(Instant.ofEpochSecond(data.getVbankDate()), ZoneId.systemDefault())
                : null;

        return PgVirtualAccountResult.builder()
                .paymentKey(data.getImpUid())
                .bankCode(data.getVbankCode())
                .bankName(data.getVbankName())
                .accountNumber(data.getVbankNum())
                .holderName(data.getVbankHolder())
                .dueDate(dueDate)
                .build();
    }

    // ─── 하위 호환 메서드 (통합 테스트에서 직접 mock 시 사용) ──────────

    /** @deprecated PgRouter 경유 권장. 통합 테스트 호환성 유지용. */
    @Deprecated
    public PortOnePaymentResponse getPaymentByImpUid(String impUid) {
        return fetchPaymentByImpUid(impUid);
    }

    /** @deprecated PgRouter 경유 권장. 통합 테스트 호환성 유지용. */
    @Deprecated
    public PortOnePaymentResponse getPaymentByMerchantUid(String merchantUid) {
        return fetchPaymentByMerchantUid(merchantUid);
    }

    /** @deprecated cancel() 사용 권장. 통합 테스트 호환성 유지용. */
    @Deprecated
    public PortOnePaymentResponse cancelPayment(PortOneCancelRequest cancelRequest) {
        return executeCancelRequest(cancelRequest);
    }

    // ─── 내부 API 호출 ────────────────────────────────────────────────

    private PortOnePaymentResponse fetchPaymentByImpUid(String impUid) {
        return portoneWebClient.get()
                .uri("/payments/{imp_uid}", impUid)
                .header("Authorization", tokenManager.getToken())
                .retrieve()
                .bodyToMono(PortOnePaymentResponse.class)
                .block();
    }

    private PortOnePaymentResponse fetchPaymentByMerchantUid(String merchantUid) {
        return portoneWebClient.get()
                .uri("/payments/merchant_uid/{merchant_uid}", merchantUid)
                .header("Authorization", tokenManager.getToken())
                .retrieve()
                .bodyToMono(PortOnePaymentResponse.class)
                .block();
    }

    private PortOnePaymentResponse executeCancelRequest(PortOneCancelRequest request) {
        return portoneWebClient.post()
                .uri("/payments/cancel")
                .header("Authorization", tokenManager.getToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(PortOnePaymentResponse.class)
                .block();
    }

    private PortOnePaymentResponse executeSubscribePayment(PortOneSubscribePaymentRequest request) {
        return portoneWebClient.post()
                .uri("/subscribe/payments/again")
                .header("Authorization", tokenManager.getToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(PortOnePaymentResponse.class)
                .block();
    }

    private PortOnePaymentResponse executeVirtualAccountRequest(PortOneVirtualAccountRequest request) {
        return portoneWebClient.post()
                .uri("/vbanks")
                .header("Authorization", tokenManager.getToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(PortOnePaymentResponse.class)
                .block();
    }

    /**
     * 401 수신 시 토큰 캐시 무효화 후 1회 재시도
     *
     * 1회만 재시도. 재시도도 실패하면 PG_AUTHENTICATION_FAILED 예외 발생.
     */
    private PortOnePaymentResponse callWithTokenRefreshOnUnauthorized(
            ApiCall<PortOnePaymentResponse> call) {
        try {
            return call.execute();
        } catch (WebClientResponseException.Unauthorized e) {
            log.warn("[PortOneClient] 401 수신 - 토큰 만료. 캐시 무효화 후 재시도");
            tokenManager.evictToken();
            return call.execute();
        }
    }

    private void validateResponse(PortOnePaymentResponse response, String errorMsg) {
        if (response == null || !response.isSuccess()) {
            log.error("[PortOneClient] API 호출 실패 - {}", errorMsg);
            throw new PaycoreException(ErrorCode.PG_API_ERROR, errorMsg);
        }
    }

    private PgPaymentDetail toPaymentDetail(PortOnePaymentResponse response) {
        PortOnePaymentResponse.PaymentData data = response.getResponse();

        PgPaymentDetail.VirtualAccountInfo vaInfo = null;
        if ("vbank".equals(data.getPayMethod()) && data.getVbankNum() != null) {
            LocalDateTime dueDate = data.getVbankDate() != null
                    ? LocalDateTime.ofInstant(Instant.ofEpochSecond(data.getVbankDate()), ZoneId.systemDefault())
                    : null;
            vaInfo = PgPaymentDetail.VirtualAccountInfo.builder()
                    .bankCode(data.getVbankCode())
                    .bankName(data.getVbankName())
                    .accountNumber(data.getVbankNum())
                    .holderName(data.getVbankHolder())
                    .dueDate(dueDate != null ? dueDate.atZone(ZoneId.systemDefault()).toEpochSecond() : null)
                    .build();
        }

        return PgPaymentDetail.builder()
                .paymentKey(data.getImpUid())
                .orderId(data.getMerchantUid())
                .status(data.getStatus())
                .payMethod(data.getPayMethod())
                .amount(data.getAmount())
                .cancelledAmount(data.getCancelAmount())
                .paidAt(data.getPaidAt())
                .virtualAccountInfo(vaInfo)
                .build();
    }

    @FunctionalInterface
    private interface ApiCall<T> {
        T execute();
    }
}
