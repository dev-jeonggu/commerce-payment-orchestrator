package com.paycore.merchant.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Merchant Redis 캐시용 DTO
 *
 * JPA 엔티티(Merchant)를 캐시하면 두 가지 문제가 발생:
 *   1. @NoArgsConstructor(PROTECTED) → Jackson 역직렬화 실패
 *   2. detached 엔티티가 캐시에서 복원되어 LazyInitException 위험
 *
 * Serializable + public 기본 생성자를 갖는 단순 DTO를 캐시.
 * JPA가 secretKey/webhookSecret을 AES 복호화한 뒤 이 DTO에 담으므로
 * 캐시에는 평문이 저장됨 — Redis는 반드시 암호화 전송(TLS) 필요.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MerchantInfo implements Serializable {

    private String merchantId;
    private String secretKey;
    private String webhookUrl;
    private String webhookSecret;
    private MerchantStatus status;

    public static MerchantInfo from(Merchant merchant) {
        return MerchantInfo.builder()
                .merchantId(merchant.getMerchantId())
                .secretKey(merchant.getSecretKey())
                .webhookUrl(merchant.getWebhookUrl())
                .webhookSecret(merchant.getWebhookSecret())
                .status(merchant.getStatus())
                .build();
    }

    public boolean isActive() {
        return this.status == MerchantStatus.ACTIVE;
    }

    public boolean isSuspended() {
        return this.status == MerchantStatus.SUSPENDED;
    }
}
