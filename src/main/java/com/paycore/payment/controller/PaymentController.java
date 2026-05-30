package com.paycore.payment.controller;

import com.paycore.common.exception.ErrorCode;
import com.paycore.common.exception.PaycoreException;
import com.paycore.common.response.ApiResponse;
import com.paycore.lock.DistributedLockService;
import com.paycore.payment.controller.dto.PaymentCancelRequest;
import com.paycore.payment.controller.dto.PaymentRequest;
import com.paycore.payment.controller.dto.PaymentResponse;
import com.paycore.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

@Tag(name = "Payment API", description = "결제 관리 API")
@Slf4j
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final DistributedLockService distributedLockService;

    @Value("${paycore.internal.webhook-token}")
    private String internalWebhookToken;

    @Operation(summary = "결제 요청", description = "카드/휴대폰/계좌이체 결제 요청. 헤더: X-Merchant-Id, X-Api-Key 필수.")
    @PostMapping
    public ApiResponse<PaymentResponse> requestPayment(@Valid @RequestBody PaymentRequest request) {
        PaymentResponse response = distributedLockService.executeWithPaymentLock(
                request.getMerchantOrderId(),
                () -> paymentService.requestPayment(request)
        );
        return ApiResponse.success(response);
    }

    @Operation(summary = "결제 취소", description = "헤더: X-Merchant-Id, X-Api-Key 필수.")
    @PostMapping("/cancel")
    public ApiResponse<PaymentResponse> cancelPayment(
            @RequestHeader("X-Merchant-Id") String merchantId,
            @Valid @RequestBody PaymentCancelRequest request) {
        PaymentResponse response = distributedLockService.executeWithPaymentLock(
                request.getMerchantOrderId(),
                () -> paymentService.cancelPayment(merchantId, request)
        );
        return ApiResponse.success("결제가 취소되었습니다.", response);
    }

    @Operation(summary = "결제 조회", description = "헤더: X-Merchant-Id, X-Api-Key 필수.")
    @GetMapping("/{merchantOrderId}")
    public ApiResponse<PaymentResponse> getPayment(
            @RequestHeader("X-Merchant-Id") String merchantId,
            @PathVariable String merchantOrderId) {
        return ApiResponse.success(paymentService.getPayment(merchantId, merchantOrderId));
    }

    /**
     * 은행/내부 시스템으로부터 입금 확인 Webhook 수신
     *
     * [인증] X-Internal-Token 헤더 검증 (고정 시크릿, 환경변수 INTERNAL_WEBHOOK_TOKEN).
     * 가맹점 인증 인터셉터 제외 경로이므로 이 메서드 내에서 직접 검증.
     *
     * [호출자] 내부 시스템 또는 은행 API 콜백. 외부 인터넷에서 직접 접근 불가하도록
     * 운영 환경에서는 네트워크 레벨(방화벽/API Gateway)로 추가 보호 권장.
     */
    @Operation(summary = "은행 입금 확인 Webhook (내부 전용)", description = "헤더: X-Internal-Token 필수.")
    @PostMapping("/webhook/bank")
    public ApiResponse<Void> receiveBankWebhook(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestParam String txId,
            @RequestParam String merchantOrderId) {

        // MessageDigest.isEqual: 상수 시간 비교로 타이밍 공격 방어
        boolean tokenValid = token != null && MessageDigest.isEqual(
                internalWebhookToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8)
        );
        if (!tokenValid) {
            log.warn("[PaymentController] 내부 Webhook 인증 실패 - txId: {}", txId);
            throw new PaycoreException(ErrorCode.WEBHOOK_SIGNATURE_INVALID, "내부 Webhook 인증에 실패했습니다.");
        }

        paymentService.processWebhook(txId, merchantOrderId);
        return ApiResponse.success("입금 확인 처리 완료", null);
    }
}
