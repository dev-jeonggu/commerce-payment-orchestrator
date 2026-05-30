package com.paycore.virtualaccount.controller.dto;

import com.paycore.virtualaccount.domain.VirtualAccount;
import com.paycore.virtualaccount.domain.VirtualAccountStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
@Schema(description = "가상계좌 발급 응답")
public class VirtualAccountResponse {

    @Schema(description = "가상계좌 ID")
    private final Long id;

    @Schema(description = "가맹점 주문번호")
    private final String merchantOrderId;

    @Schema(description = "내부 트랜잭션 ID")
    private final String txId;

    @Schema(description = "은행 코드")
    private final String bankCode;

    @Schema(description = "은행명")
    private final String bankName;

    @Schema(description = "가상계좌 번호 (고객에게 안내할 계좌)")
    private final String accountNumber;

    @Schema(description = "예금주명")
    private final String holderName;

    @Schema(description = "결제 금액")
    private final Long amount;

    @Schema(description = "입금 기한")
    private final LocalDateTime dueDate;

    @Schema(description = "가상계좌 상태 (ISSUED/DEPOSITED/EXPIRED)")
    private final VirtualAccountStatus status;

    @Schema(description = "발급 시각")
    private final LocalDateTime createdAt;

    @Schema(description = "입금 확인 시각 (입금 전: null)")
    private final LocalDateTime depositedAt;

    public static VirtualAccountResponse of(VirtualAccount va) {
        return VirtualAccountResponse.builder()
                .id(va.getId())
                .merchantOrderId(va.getMerchantOrderId())
                .txId(va.getTxId())
                .bankCode(va.getBankCode())
                .bankName(va.getBankName())
                .accountNumber(va.getAccountNumber())
                .holderName(va.getHolderName())
                .amount(va.getAmount())
                .dueDate(va.getDueDate())
                .status(va.getStatus())
                .createdAt(va.getCreatedAt())
                .depositedAt(va.getDepositedAt())
                .build();
    }
}
