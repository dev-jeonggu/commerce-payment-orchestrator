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

@Tag(name = "Virtual Account API", description = "가상계좌 발급 및 조회")
@Slf4j
@RestController
@RequestMapping("/api/v1/virtual-accounts")
@RequiredArgsConstructor
public class VirtualAccountController {

    private final VirtualAccountService virtualAccountService;

    @Operation(summary = "가상계좌 발급")
    @PostMapping
    public ApiResponse<VirtualAccountResponse> issue(@Valid @RequestBody VirtualAccountIssueRequest request) {
        try {
            VirtualAccountResponse response = virtualAccountService.issue(request);
            return ApiResponse.success("가상계좌 발급 완료", response);
        } catch (VirtualAccountService.AlreadyIssuedVirtualAccountException e) {
            return ApiResponse.success("이미 발급된 가상계좌 정보를 반환합니다.",
                    VirtualAccountResponse.of(e.virtualAccount));
        }
    }

    @Operation(summary = "가상계좌 조회")
    @GetMapping("/{merchantOrderId}")
    public ApiResponse<VirtualAccountResponse> get(@PathVariable String merchantOrderId) {
        return ApiResponse.success(virtualAccountService.getByMerchantOrderId(merchantOrderId));
    }
}
