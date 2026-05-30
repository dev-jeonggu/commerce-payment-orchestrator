package com.paycore.payment.method.cmd;

import lombok.Builder;
import lombok.Getter;

/**
 * [주의] pgBillingKey는 복호화된 원본 키. 서비스 레이어에서 복호화 후 전달해야 함.
 */
@Getter
@Builder
public class BillingCommand {
    private final String pgBillingKey;
    private final String orderId;
    private final Long amount;
    private final String orderName;
    private final String customerKey;
}
