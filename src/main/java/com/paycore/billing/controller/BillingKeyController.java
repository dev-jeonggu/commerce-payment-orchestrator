package com.paycore.billing.controller;

import com.paycore.billing.controller.dto.BillingKeyChargeRequest;
import com.paycore.billing.controller.dto.BillingKeyChargeResponse;
import com.paycore.billing.controller.dto.BillingKeyRegisterRequest;
import com.paycore.billing.controller.dto.BillingKeyResponse;
import com.paycore.billing.service.BillingKeyService;
import com.paycore.common.response.ApiResponse;
import com.paycore.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 빌링키(자동결제) API
 *
 * [보안 주의]
 * 실운영에서는 모든 엔드포인트에 JWT 인증 + userId 파라미터 제거.
 * JWT 토큰에서 userId를 추출하여 타인의 빌링키 접근 차단.
 * 현재 userId를 Request Body로 받는 것은 데모 목적.
 */
@Tag(name = "Billing Key API", description = "자동결제/정기결제 빌링키 관리")
@Slf4j
@RestController
@RequestMapping("/api/v1/billing-keys")
@RequiredArgsConstructor
public class BillingKeyController {

    private final BillingKeyService billingKeyService;
    private final PaymentService paymentService;

    @Operation(
        summary = "빌링키 등록",
        description = """
            PG JS SDK로 등록된 customer_uid(빌링키)를 서버에 저장.

            **카드번호 보안**
            - 실제 카드번호는 클라이언트 → PG사 직접 전송 (서버 미통과)
            - 서버는 PG사가 발급한 customer_uid만 수신 → AES-256 암호화 저장
            - DB에는 암호화된 빌링키 + 마스킹 카드번호만 저장
            """
    )
    @PostMapping
    public ApiResponse<BillingKeyResponse> register(
            @Valid @RequestBody BillingKeyRegisterRequest request) {
        return ApiResponse.success("빌링키 등록 완료", billingKeyService.register(request));
    }

    @Operation(
        summary = "빌링키 목록 조회",
        description = "사용자의 등록된 빌링키 목록 조회. 복호화된 빌링키는 포함되지 않음."
    )
    @GetMapping
    public ApiResponse<List<BillingKeyResponse>> getList(@RequestParam Long userId) {
        return ApiResponse.success(billingKeyService.getList(userId));
    }

    @Operation(
        summary = "빌링키 결제 (자동결제)",
        description = """
            등록된 빌링키로 결제 실행.

            **선행 조건**: orderId에 해당하는 주문이 반드시 존재해야 함. 주문 없이 결제 불가.

            **처리 순서**
            1. 주문 상태 확인 (이미 결제된 주문이면 409)
            2. 중복 결제 방지 체크
            3. PG API 빌링키 결제 실행
            4. Payment 저장 + Order.PAID 처리
            5. 재고 차감 + 포인트 적립 (후처리, 실패 시 Saga 보상)

            **용도**: 구독 서비스 정기결제, 앱카드 간편결제
            **주의**: 서버가 사용자 동의 없이 임의로 결제 실행 불가 → 호출 시 사용자 동의 확인 필수
            """
    )
    @PostMapping("/charge")
    public ApiResponse<BillingKeyChargeResponse> charge(@Valid @RequestBody BillingKeyChargeRequest request) {
        // TX1: PG API 호출 + Payment 저장 + Order.PAID
        BillingKeyChargeResponse response = billingKeyService.charge(request);

        // TX2: 재고 차감 + 포인트 적립 (카드 결제와 동일한 후처리 패턴)
        try {
            paymentService.processAfterPayment(request.getOrderId());
        } catch (Exception e) {
            log.warn("[BillingKeyController] 빌링키 결제 후처리 실패 (Saga 보상 완료) - orderId: {}",
                    request.getOrderId());
            return ApiResponse.error("결제는 완료됐으나 후처리 실패로 자동 취소되었습니다.");
        }

        return ApiResponse.success("빌링키 결제 완료", response);
    }

    @Operation(
        summary = "빌링키 삭제",
        description = "빌링키 소프트 삭제. 삭제 후 자동결제 불가. 복구 불가."
    )
    @DeleteMapping("/{billingKeyId}")
    public ApiResponse<Void> delete(
            @PathVariable Long billingKeyId,
            @RequestParam Long userId) {
        billingKeyService.delete(billingKeyId, userId);
        return ApiResponse.success("빌링키 삭제 완료", null);
    }
}
