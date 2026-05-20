package com.paycore.payment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paycore.common.response.ApiResponse;
import com.paycore.lock.DistributedLockService;
import com.paycore.payment.client.PortOneWebhookVerifier;
import com.paycore.payment.controller.dto.PaymentCancelRequest;
import com.paycore.payment.controller.dto.PaymentResponse;
import com.paycore.payment.controller.dto.PaymentVerifyRequest;
import com.paycore.payment.controller.dto.PaymentWebhookRequest;
import com.paycore.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
    private final PortOneWebhookVerifier webhookVerifier;
    private final ObjectMapper objectMapper;

    @Operation(
        summary = "결제 검증 (사후 검증)",
        description = """
            PG 결제 완료 후 금액 검증 및 결제 확정

            **처리 순서**
            1. Redis 분산락 획득 (중복 요청 방지)
            2. PG API 단건 조회 (클라이언트 데이터 신뢰 X)
            3. DB 주문 금액 vs PG 결제 금액 비교
            4. 일치하면 PAID 처리, 재고 차감, 포인트 적립
            5. 불일치하면 즉시 PG 취소 + 예외 반환
            6. 락 해제

            **포인트**: 이 금액 검증 로직이 결제 위변조를 방지하는 핵심
            """
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "결제 검증 성공",
            content = @Content(schema = @Schema(implementation = PaymentResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400", description = "금액 불일치 / 입력값 오류",
            content = @Content(schema = @Schema(example = "{\"success\":false,\"message\":\"결제 금액이 주문 금액과 일치하지 않습니다.\"}"))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404", description = "주문을 찾을 수 없음",
            content = @Content(schema = @Schema(example = "{\"success\":false,\"message\":\"주문을 찾을 수 없습니다.\"}"))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409", description = "이미 처리된 결제 또는 중복 요청 (분산락)",
            content = @Content(schema = @Schema(example = "{\"success\":false,\"message\":\"이미 처리된 결제입니다.\"}")))
    })
    @PostMapping("/verify")
    public ApiResponse<PaymentResponse> verifyPayment(@Valid @RequestBody PaymentVerifyRequest request) {
        PaymentResponse response = distributedLockService.executeWithPaymentLock(
                request.getMerchantUid(),
                () -> paymentService.verifyAndSavePayment(request)
        );
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
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "Webhook 처리 완료 (멱등성 보장 - 중복 수신 시에도 200)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404", description = "해당 주문 없음",
            content = @Content(schema = @Schema(example = "{\"success\":false,\"message\":\"주문을 찾을 수 없습니다.\"}")))
    })
    @PostMapping("/webhook")
    public ApiResponse<Void> receiveWebhook(
            @RequestHeader(value = "X-Imp-Signature", required = false) String signature,
            @RequestBody String rawBody) throws com.fasterxml.jackson.core.JsonProcessingException {

        // [보안 수정] PortOne HMAC-SHA256 서명 검증 (위조된 Webhook 차단)
        // 서명 없는 요청은 외부 공격자가 임의로 결제 완료 이벤트를 주입할 수 있음.
        webhookVerifier.verify(signature, rawBody);

        PaymentWebhookRequest request = objectMapper.readValue(rawBody, PaymentWebhookRequest.class);

        log.info("[Webhook] 수신 - merchantUid: {}, impUid: {}, status: {}",
                request.getMerchantUid(), request.getImpUid(), request.getStatus());

        boolean wasNewlyPaid = paymentService.processWebhook(
                request.getImpUid(), request.getMerchantUid());

        // 일반 카드/계좌이체 결제만 후처리 실행.
        // 가상계좌는 processWebhook 내부 processDeposit에서 재고/포인트까지 이미 처리하므로
        // wasNewlyPaid=false를 반환 → processAfterWebhook이 processAfterPayment를 호출하지 않음.
        paymentService.processAfterWebhook(request.getMerchantUid(), wasNewlyPaid);

        return ApiResponse.success("Webhook 처리 완료", null);
    }

    @Operation(
        summary = "결제 취소",
        description = """
            결제 취소 요청

            - `amount` 미입력 시 전액 취소 → 주문 상태 CANCELLED
            - `amount` 입력 시 부분 취소 → 주문 상태 PAID 유지, 결제 상태 PARTIAL_CANCELLED
            - 동일 주문 동시 취소 요청은 분산락으로 직렬화
            """
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "취소 성공",
            content = @Content(schema = @Schema(implementation = PaymentResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400", description = "결제 완료 상태가 아닌 주문",
            content = @Content(schema = @Schema(example = "{\"success\":false,\"message\":\"결제 완료 상태의 주문만 취소할 수 있습니다.\"}"))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404", description = "주문 또는 결제 정보 없음",
            content = @Content(schema = @Schema(example = "{\"success\":false,\"message\":\"결제 정보를 찾을 수 없습니다.\"}"))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409", description = "동시 취소 요청 충돌 (분산락)",
            content = @Content(schema = @Schema(example = "{\"success\":false,\"message\":\"현재 처리 중인 요청이 있습니다. 잠시 후 다시 시도해주세요.\"}")))
    })
    @PostMapping("/cancel")
    public ApiResponse<PaymentResponse> cancelPayment(@Valid @RequestBody PaymentCancelRequest request) {
        PaymentResponse response = distributedLockService.executeWithPaymentLock(
                request.getMerchantUid(),
                () -> paymentService.cancelPayment(request)
        );
        return ApiResponse.success("결제가 취소되었습니다.", response);
    }

    @Operation(
        summary = "결제 조회",
        description = "주문번호로 결제 정보 조회"
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "조회 성공",
            content = @Content(schema = @Schema(implementation = PaymentResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404", description = "주문 또는 결제 정보 없음",
            content = @Content(schema = @Schema(example = "{\"success\":false,\"message\":\"결제 정보를 찾을 수 없습니다.\"}")))
    })
    @GetMapping("/{orderNo}")
    public ApiResponse<PaymentResponse> getPayment(@PathVariable String orderNo) {
        return ApiResponse.success(paymentService.getPayment(orderNo));
    }
}
