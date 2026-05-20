package com.paycore.billing.controller.dto;

import com.paycore.billing.domain.BillingKey;
import com.paycore.payment.pg.PgProvider;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class BillingKeyResponse {

    private final Long id;
    private final Long userId;
    private final String maskedCardNo;
    private final String cardCompany;
    private final PgProvider pgProvider;
    private final boolean isDefault;
    private final LocalDateTime createdAt;

    public static BillingKeyResponse of(BillingKey billingKey) {
        return BillingKeyResponse.builder()
                .id(billingKey.getId())
                .userId(billingKey.getUserId())
                .maskedCardNo(billingKey.getMaskedCardNo())
                .cardCompany(billingKey.getCardCompany())
                .pgProvider(billingKey.getPgProvider())
                .isDefault(billingKey.isDefault())
                .createdAt(billingKey.getCreatedAt())
                .build();
    }
}
