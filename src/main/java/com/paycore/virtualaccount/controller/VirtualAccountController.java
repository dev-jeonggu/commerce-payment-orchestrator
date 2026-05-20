package com.paycore.virtualaccount.controller;

import com.paycore.common.response.ApiResponse;
import com.paycore.virtualaccount.controller.dto.VirtualAccountIssueRequest;
import com.paycore.virtualaccount.controller.dto.VirtualAccountResponse;
import com.paycore.virtualaccount.service.VirtualAccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 가상계좌 API
 *
 * [보안 주의]
 * 실운영에서는 JWT 인증 필수 — orderNo가 타인의 주문인지 검증.
 * 현재 userId 검증 없는 것은 데모 목적.
 *
 * [Webhook 처리]
 * 가상계좌 입금 확인은 /api/v1/payments/webhook 으로 PG사가 직접 호출.
 * VirtualAccountController가 아닌 PaymentController.receiveWebhook이 처리 진입점.
 */
@Tag(name = "Virtual Account API", description = "가상계좌 발급 및 조회")
@Slf4j
@RestController
@RequestMapping("/api/v1/virtual-accounts")
@RequiredArgsConstructor
public class VirtualAccountController {

    private final VirtualAccountService virtualAccountService;

    @Operation(
        summary = "가상계좌 발급",
        description = """
            주문에 대한 가상계좌를 PG사를 통해 발급합니다.

            **처리 순서**
            1. 주문 상태 확인 (PENDING 또는 EXPIRED VA면 재발급 가능)
            2. PG API로 가상계좌 발급 요청
            3. 발급된 계좌 정보 DB 저장
            4. 주문 상태 → PENDING_PAYMENT (입금 대기)

            **입금 확인**
            - 고객이 발급된 계좌로 입금 완료 시 PG사가 Webhook(/api/v1/payments/webhook) 전송
            - Webhook이 누락되어도 스케줄러(5분 주기)가 PENDING_PAYMENT 주문을 재조회해 보완

            **멱등성**
            - 동일 주문에 이미 ISSUED 상태 VA가 있으면 새로 발급하지 않고 기존 정보 반환
            """
    )
    @PostMapping
    public ApiResponse<VirtualAccountResponse> issue(
            @Valid @RequestBody VirtualAccountIssueRequest request) {
        try {
            VirtualAccountResponse response = virtualAccountService.issue(request);
            return ApiResponse.success("가상계좌 발급 완료", response);
        } catch (VirtualAccountService.AlreadyIssuedVirtualAccountException e) {
            // 이미 발급된 VA가 있으면 기존 것 반환 (멱등성)
            return ApiResponse.success("이미 발급된 가상계좌 정보를 반환합니다.",
                    VirtualAccountResponse.of(e.virtualAccount));
        }
    }

    @Operation(
        summary = "가상계좌 조회",
        description = "주문번호로 가상계좌 정보를 조회합니다. 입금 대기 중인 계좌 번호 확인에 사용."
    )
    @GetMapping("/{orderNo}")
    public ApiResponse<VirtualAccountResponse> get(@PathVariable String orderNo) {
        return ApiResponse.success(virtualAccountService.getByOrderNo(orderNo));
    }
}
