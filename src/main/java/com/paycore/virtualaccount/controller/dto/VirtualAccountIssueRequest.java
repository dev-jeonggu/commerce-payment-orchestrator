package com.paycore.virtualaccount.controller.dto;

import com.paycore.payment.pg.PgProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@Schema(description = "가상계좌 발급 요청")
public class VirtualAccountIssueRequest {

    @NotBlank
    @Schema(description = "가맹점 주문번호 (Order.orderNo)")
    private String orderNo;

    @NotNull
    @Positive
    @Schema(description = "결제 금액")
    private Long amount;

    @NotBlank
    @Schema(description = "주문명 (결제 화면에 표시)")
    private String orderName;

    /**
     * 은행 코드
     * PortOne 기준: "004"=KB국민, "088"=신한, "020"=우리, "081"=KEB하나 등
     *
     */
    @NotBlank
    @Schema(description = "은행 코드 (PortOne 기준: 004=KB국민, 088=신한, 020=우리)")
    private String bankCode;

    @NotBlank
    @Schema(description = "예금주명 (가맹점명 또는 서비스명)")
    private String holderName;

    /**
     * 입금 기한
     *
     */
    @Schema(description = "입금 기한 (null 시 서버 기본값 3일 적용)")
    private LocalDateTime dueDate;

    @Schema(description = "PG사 (기본값: PORTONE)")
    private PgProvider pgProvider;
}
