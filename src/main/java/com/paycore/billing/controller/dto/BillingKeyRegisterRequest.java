package com.paycore.billing.controller.dto;

import com.paycore.payment.pg.PgProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@Schema(description = "빌링키 등록 요청")
public class BillingKeyRegisterRequest {

    @NotNull
    @Schema(description = "사용자 ID", example = "1")
    private Long userId;

    @NotBlank
    @Schema(description = "PG사 발급 빌링키 (PortOne: customer_uid)",
            example = "customer_1234")
    private String pgBillingKey;

    @Schema(description = "마스킹된 카드번호", example = "123456******3456")
    private String maskedCardNo;

    @Schema(description = "카드사", example = "SHINHAN")
    private String cardCompany;

    @Schema(description = "PG사", example = "PORTONE", defaultValue = "PORTONE")
    private PgProvider pgProvider;

    @Schema(description = "기본 결제 수단 설정 여부", example = "true", defaultValue = "false")
    private boolean isDefault;
}
