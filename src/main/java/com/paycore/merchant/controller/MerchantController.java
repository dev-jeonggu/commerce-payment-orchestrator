package com.paycore.merchant.controller;

import com.paycore.common.response.ApiResponse;
import com.paycore.merchant.controller.dto.MerchantCreateRequest;
import com.paycore.merchant.controller.dto.MerchantResponse;
import com.paycore.merchant.service.MerchantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Merchant API", description = "가맹점 관리 API")
@RestController
@RequestMapping("/api/v1/merchants")
@RequiredArgsConstructor
public class MerchantController {

    private final MerchantService merchantService;

    @Operation(summary = "가맹점 등록")
    @PostMapping
    public ApiResponse<MerchantResponse> register(@Valid @RequestBody MerchantCreateRequest request) {
        return ApiResponse.success(merchantService.register(request));
    }

    @Operation(summary = "가맹점 조회")
    @GetMapping("/{merchantId}")
    public ApiResponse<MerchantResponse> getMerchant(@PathVariable String merchantId) {
        return ApiResponse.success(merchantService.getMerchant(merchantId));
    }
}
