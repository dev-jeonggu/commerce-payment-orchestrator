package com.paycore.payment.controller;

import com.paycore.common.response.ApiResponse;
import com.paycore.lock.DistributedLockService;
import com.paycore.payment.controller.dto.PaymentCancelRequest;
import com.paycore.payment.controller.dto.PaymentResponse;
import com.paycore.payment.controller.dto.PaymentVerifyRequest;
import com.paycore.payment.controller.dto.PaymentWebhookRequest;
import com.paycore.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Payment API", description = "결제 관리 API")
@Slf4j
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final DistributedLockService distributedLockService;

    @Operation(
        summary = "결제 검증 (사후 검증)",
        description = """
            PG 결제 완료 후 금액 검증 및 결제 확정

            **처리 순서**
            1. Redis 분산락 획득 (중복 요청 방지)
            2. PG API 단건 조회 (클라이언트 데이터 신뢰 X)
            3. DB 주문 금액 vs PG 결제 금액 비교
            4. 일치하면 PAID 처리
            5. 불일치하면 즉시 PG 취소 + 예외 반환
            6. 락 해제

            **포인트**: 이 금액 검증 로직이 결제 위변조를 방지하는 핵심
            """
    )
    @PostMapping("/verify")
    public ApiResponse<PaymentResponse> verifyPayment(@Valid @RequestBody PaymentVerifyRequest request) {
        // 1단계: 분산락 + 결제 금액 검증 → PAID 확정 (트랜잭션 커밋)
        PaymentResponse response = distributedLockService.executeWithPaymentLock(
                request.getMerchantUid(),
                () -> paymentService.verifyAndSavePayment(request)
        );
        // 2단계: 재고 차감, 포인트 적립 (실패 시 Saga 보상 취소)
        try {
            paymentService.processAfterPayment(request.getMerchantUid());
        } catch (Exception e) {
            log.warn("[PaymentController] 결제 후처리 실패 (Saga 취소 완료) - orderNo: {}",
                    request.getMerchantUid());
            return ApiResponse.error("결제 후처리 실패로 자동 취소되었습니다.");
        }
        return ApiResponse.success(response);
    }

    @Operation(
        summary = "Webhook 수신",
        description = """
            PG사로부터 결제 상태 변경 알림 수신

            **핵심**: Webhook 내용은 절대 신뢰하지 않음
            - 반드시 PG 단건 조회 API로 실제 상태 재확인
            - Webhook 유실 대비 → 스케줄러(5분 주기)가 보완
            """
    )
    @PostMapping("/webhook")
    public ApiResponse<Void> receiveWebhook(@RequestBody PaymentWebhookRequest request) {
        log.info("[Webhook] 수신 - merchantUid: {}, impUid: {}, status: {}",
                request.getMerchantUid(), request.getImpUid(), request.getStatus());

        paymentService.processWebhook(request.getImpUid(), request.getMerchantUid());
        return ApiResponse.success("Webhook 처리 완료", null);
    }

    @Operation(
        summary = "결제 취소",
        description = """
            결제 취소 요청

            - amount 미입력 시 전액 취소
            - amount 입력 시 부분 취소
            """
    )
    @PostMapping("/cancel")
    public ApiResponse<PaymentResponse> cancelPayment(@Valid @RequestBody PaymentCancelRequest request) {
        return ApiResponse.success("결제가 취소되었습니다.", paymentService.cancelPayment(request));
    }

    @Operation(
        summary = "결제 조회",
        description = "주문번호로 결제 정보 조회"
    )
    @GetMapping("/{orderNo}")
    public ApiResponse<PaymentResponse> getPayment(@PathVariable String orderNo) {
        return ApiResponse.success(paymentService.getPayment(orderNo));
    }
}
