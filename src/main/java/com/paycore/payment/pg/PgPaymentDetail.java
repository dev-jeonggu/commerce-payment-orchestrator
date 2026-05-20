package com.paycore.payment.pg;

import lombok.Builder;
import lombok.Getter;

/**
 * PG사 공통 결제 상세 응답
 *
 * PG사마다 필드명이 다름:
 *   PortOne: imp_uid / merchant_uid / pay_method
 *   Toss:    paymentKey / orderId / method
 *   KakaoPay: tid / partner_order_id / payment_method_type
 *
 * 이 DTO가 그 차이를 흡수함.
 */
@Getter
@Builder
public class PgPaymentDetail {

    /** PG사 고유 결제 키 (PortOne: imp_uid, Toss: paymentKey) */
    private final String paymentKey;

    /** 가맹점 주문번호 (= orderNo) */
    private final String orderId;

    /** 결제 상태 (paid / cancelled / failed / ready) */
    private final String status;

    /** 결제 수단 (card / vbank / trans 등) */
    private final String payMethod;

    /** 결제 금액 */
    private final Long amount;

    /** 취소된 금액 */
    private final Long cancelledAmount;

    /** 결제 완료 시각 (Unix timestamp) */
    private final Long paidAt;

    /** 가상계좌 발급 정보 (결제 수단이 vbank인 경우에만 존재) */
    private final VirtualAccountInfo virtualAccountInfo;

    public boolean isPaid() {
        return "paid".equals(status);
    }

    public boolean isCancelled() {
        return "cancelled".equals(status);
    }

    public boolean isVirtualAccount() {
        return "vbank".equals(payMethod);
    }

    @Getter
    @Builder
    public static class VirtualAccountInfo {
        private final String bankCode;
        private final String bankName;
        private final String accountNumber;
        private final String holderName;
        private final Long dueDate;  // Unix timestamp (입금 기한)
    }
}
