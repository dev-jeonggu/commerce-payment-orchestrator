package com.paycore.payment.pg;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * PG사 공통 가상계좌 발급 요청
 *
 * [설계 논란] 은행 코드 없이 발급 가능한가?
 *   PortOne: bankCode 필수 (어떤 은행의 계좌인지 지정)
 *   실무에서는 사용자가 은행 선택 후 발급 요청
 */
@Getter
@Builder
public class PgVirtualAccountCommand {
    private final String orderId;
    private final Long amount;
    private final String orderName;
    private final String bankCode;
    private final String holderName;
    private final LocalDateTime dueDate;
}
