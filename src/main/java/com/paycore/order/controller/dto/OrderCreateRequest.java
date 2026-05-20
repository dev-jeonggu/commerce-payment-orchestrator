package com.paycore.order.controller.dto;

import com.paycore.payment.pg.PgProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@Schema(description = "주문 생성 요청")
public class OrderCreateRequest {

    @NotNull(message = "userId는 필수입니다.")
    @Schema(description = "사용자 ID", example = "1")
    private Long userId;

    @NotNull(message = "itemId는 필수입니다.")
    @Schema(description = "상품 ID", example = "10")
    private Long itemId;

    @NotNull(message = "금액은 필수입니다.")
    @Min(value = 100, message = "최소 결제 금액은 100원입니다.")
    @Schema(description = "결제 금액 (서버에서 상품 가격으로 재검증)", example = "30000")
    private Long amount;

    @Schema(description = "PG사 선택 (미입력 시 PORTONE 기본값)", example = "PORTONE", defaultValue = "PORTONE")
    private PgProvider pgProvider;
}
