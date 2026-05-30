package com.paycore.merchant.controller.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class MerchantCreateRequest {

    @NotBlank
    private String merchantId;

    @NotBlank
    private String webhookUrl;

    @NotBlank
    private String webhookSecret;
}
