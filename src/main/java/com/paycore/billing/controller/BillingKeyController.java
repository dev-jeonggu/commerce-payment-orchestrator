package com.paycore.billing.controller;

import com.paycore.billing.controller.dto.BillingKeyChargeRequest;
import com.paycore.billing.controller.dto.BillingKeyChargeResponse;
import com.paycore.billing.controller.dto.BillingKeyRegisterRequest;
import com.paycore.billing.controller.dto.BillingKeyResponse;
import com.paycore.billing.service.BillingKeyService;
import com.paycore.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Billing Key API", description = "자동결제/정기결제 빌링키 관리")
@Slf4j
@RestController
@RequestMapping("/api/v1/billing-keys")
@RequiredArgsConstructor
public class BillingKeyController {

    private final BillingKeyService billingKeyService;

    @Operation(summary = "빌링키 등록")
    @PostMapping
    public ApiResponse<BillingKeyResponse> register(@Valid @RequestBody BillingKeyRegisterRequest request) {
        return ApiResponse.success("빌링키 등록 완료", billingKeyService.register(request));
    }

    @Operation(summary = "빌링키 목록 조회")
    @GetMapping
    public ApiResponse<List<BillingKeyResponse>> getList(@RequestParam Long userId) {
        return ApiResponse.success(billingKeyService.getList(userId));
    }

    @Operation(summary = "빌링키 결제 (자동결제)")
    @PostMapping("/charge")
    public ApiResponse<BillingKeyChargeResponse> charge(@Valid @RequestBody BillingKeyChargeRequest request) {
        BillingKeyChargeResponse response = billingKeyService.charge(request);
        return ApiResponse.success("빌링키 결제 완료", response);
    }

    @Operation(summary = "빌링키 삭제")
    @DeleteMapping("/{billingKeyId}")
    public ApiResponse<Void> delete(@PathVariable Long billingKeyId, @RequestParam Long userId) {
        billingKeyService.delete(billingKeyId, userId);
        return ApiResponse.success("빌링키 삭제 완료", null);
    }
}
