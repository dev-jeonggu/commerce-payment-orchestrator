package com.paycore.merchant.service;

import com.paycore.common.exception.ErrorCode;
import com.paycore.common.exception.PaycoreException;
import com.paycore.merchant.controller.dto.MerchantCreateRequest;
import com.paycore.merchant.controller.dto.MerchantResponse;
import com.paycore.merchant.domain.Merchant;
import com.paycore.merchant.repository.MerchantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MerchantService {

    private final MerchantRepository merchantRepository;

    @Transactional
    public MerchantResponse register(MerchantCreateRequest request) {
        if (merchantRepository.existsByMerchantId(request.getMerchantId())) {
            throw new PaycoreException(ErrorCode.MERCHANT_ALREADY_EXISTS);
        }

        String secretKey = UUID.randomUUID().toString().replace("-", "");

        Merchant merchant = Merchant.builder()
                .merchantId(request.getMerchantId())
                .secretKey(secretKey)
                .webhookUrl(request.getWebhookUrl())
                .webhookSecret(request.getWebhookSecret())
                .build();

        merchantRepository.save(merchant);
        log.info("[MerchantService] 가맹점 등록 - merchantId: {}", request.getMerchantId());
        return MerchantResponse.of(merchant);
    }

    @Transactional(readOnly = true)
    public MerchantResponse getMerchant(String merchantId) {
        Merchant merchant = getMerchantOrThrow(merchantId);
        return MerchantResponse.of(merchant);
    }

    @Transactional(readOnly = true)
    public Merchant getMerchantOrThrow(String merchantId) {
        return merchantRepository.findByMerchantId(merchantId)
                .orElseThrow(() -> new PaycoreException(ErrorCode.MERCHANT_NOT_FOUND));
    }
}
