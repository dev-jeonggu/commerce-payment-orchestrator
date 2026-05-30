package com.paycore.merchant.controller.dto;

import com.paycore.merchant.domain.Merchant;
import com.paycore.merchant.domain.MerchantStatus;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class MerchantResponse {

    private String merchantId;
    private String webhookUrl;
    private MerchantStatus status;

    public static MerchantResponse of(Merchant merchant) {
        return MerchantResponse.builder()
                .merchantId(merchant.getMerchantId())
                .webhookUrl(merchant.getWebhookUrl())
                .status(merchant.getStatus())
                .build();
    }
}
