package com.paycore.billing.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@Schema(description = "빌링키 등록 요청")
public class BillingKeyRegisterRequest {

    @NotBlank
    @Schema(description = "가맹점 ID", example = "A010084434")
    private String merchantId;

    @NotNull
    @Schema(description = "사용자 ID", example = "1")
    private Long userId;

    @NotBlank
    @Schema(description = "빌링키 (결제 수단 등록 시 발급받은 토큰)")
    private String pgBillingKey;

    @Schema(description = "마스킹된 카드번호", example = "123456******3456")
    private String maskedCardNo;

    @Schema(description = "카드사", example = "SHINHAN")
    private String cardCompany;

    @Schema(description = "기본 결제 수단 설정 여부", example = "true", defaultValue = "false")
    private boolean isDefault;
}
