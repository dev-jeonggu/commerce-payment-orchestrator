package com.paycore.payment.pg;

import lombok.Builder;
import lombok.Getter;

/**
 * PG사 공통 빌링키 결제 요청 (자동결제/정기결제)
 *
 * [주의] pgBillingKey는 복호화된 원본 키. 서비스 레이어에서 복호화 후 전달해야 함.
 */
@Getter
@Builder
public class PgBillingCommand {
    /** PG사에 저장된 빌링키 (PortOne: customer_uid) */
    private final String pgBillingKey;

    /** 가맹점 주문번호 */
    private final String orderId;

    /** 결제 금액 */
    private final Long amount;

    /** 주문명 (결제창/영수증에 표시) */
    private final String orderName;

    /** 고객 식별자 (일부 PG에서 필요) */
    private final String customerKey;
}
