package com.paycore.payment.pg;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * PG사 공통 가상계좌 발급 응답
 *
 * PortOne은 가상계좌 발급 시점에 imp_uid를 이미 부여함.
 * 입금 전이라도 paymentKey로 단건 조회 가능.
 */
@Getter
@Builder
public class PgVirtualAccountResult {
    /** PG사 결제 키 (입금 전이라도 부여됨) */
    private final String paymentKey;

    private final String bankCode;
    private final String bankName;
    private final String accountNumber;
    private final String holderName;
    private final LocalDateTime dueDate;
}
