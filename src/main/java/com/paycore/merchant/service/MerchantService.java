package com.paycore.merchant.service;

import com.paycore.common.exception.ErrorCode;
import com.paycore.common.exception.PaycoreException;
import com.paycore.merchant.controller.dto.MerchantCreateRequest;
import com.paycore.merchant.controller.dto.MerchantResponse;
import com.paycore.merchant.domain.Merchant;
import com.paycore.merchant.domain.MerchantInfo;
import com.paycore.merchant.repository.MerchantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;

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

        byte[] keyBytes = new byte[32];
        new SecureRandom().nextBytes(keyBytes);
        String secretKey = Base64.getUrlEncoder().withoutPadding().encodeToString(keyBytes);

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
        return MerchantResponse.of(getMerchantOrThrow(merchantId));
    }

    /**
     * JPA 엔티티 반환 — 캐시하지 않음.
     * Merchant 엔티티는 @NoArgsConstructor(PROTECTED)로 Jackson 역직렬화가 불가하여
     * 캐시하면 캐시 히트 시 역직렬화 예외가 발생한다.
     * 캐시가 필요한 경로는 getMerchantInfoCached() 사용.
     */
    @Transactional(readOnly = true)
    public Merchant getMerchantOrThrow(String merchantId) {
        return merchantRepository.findByMerchantId(merchantId)
                .orElseThrow(() -> new PaycoreException(ErrorCode.MERCHANT_NOT_FOUND));
    }

    /**
     * 인증/빠른 조회용 캐시 메서드 — MerchantInfo DTO 반환.
     *
     * MerchantInfo는 직렬화 가능한 단순 DTO이므로 Redis 캐시 안전.
     * 주 사용처: MerchantAuthInterceptor (모든 API 요청마다 호출).
     * TTL: CacheConfig에서 5분으로 설정.
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "merchants", key = "#merchantId")
    public MerchantInfo getMerchantInfoCached(String merchantId) {
        Merchant merchant = merchantRepository.findByMerchantId(merchantId)
                .orElseThrow(() -> new PaycoreException(ErrorCode.MERCHANT_NOT_FOUND));
        return MerchantInfo.from(merchant);
    }

    /**
     * 가맹점 정보 변경 시 캐시 무효화.
     * 현재 update/delete API가 없으므로 향후 구현 시 이 어노테이션을 사용.
     */
    @CacheEvict(value = "merchants", key = "#merchantId")
    public void evictMerchantCache(String merchantId) {
        log.info("[MerchantService] 가맹점 캐시 무효화 - merchantId: {}", merchantId);
    }
}
