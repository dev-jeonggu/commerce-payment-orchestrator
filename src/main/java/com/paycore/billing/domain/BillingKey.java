package com.paycore.billing.domain;

import com.paycore.billing.crypto.AES256Converter;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 빌링키(자동결제 토큰) 엔티티
 *
 * [보안 설계]
 * - pgBillingKey: PG사가 발급한 customer_uid. AES-256으로 암호화하여 DB 저장.
 * - 실제 카드번호는 우리 서버를 통과하지 않음 (PG JS SDK에서 직접 PG사 전송).
 * - maskedCardNo: 마스킹된 카드번호 (UI 표시용). 평문 저장 OK.
 *
 * [소프트 삭제] deleted=true → 해당 빌링키로 결제 불가, 목록에서 숨김.
 */
@Entity
@Table(
    name = "billing_keys",
    indexes = {
        @Index(name = "idx_billing_key_user", columnList = "user_id, deleted")
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class BillingKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * PG사가 발급한 빌링키 (PortOne: customer_uid)
     * AES-256 암호화 저장
     */
    @Convert(converter = AES256Converter.class)
    @Column(name = "pg_billing_key", nullable = false, length = 500)
    private String pgBillingKey;

    @Column(name = "masked_card_no")
    private String maskedCardNo;

    @Column(name = "card_company")
    private String cardCompany;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(name = "deleted", nullable = false)
    private boolean deleted;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Builder
    public BillingKey(String merchantId, Long userId, String pgBillingKey,
                      String maskedCardNo, String cardCompany, boolean isDefault) {
        this.merchantId = merchantId;
        this.userId = userId;
        this.pgBillingKey = pgBillingKey;
        this.maskedCardNo = maskedCardNo;
        this.cardCompany = cardCompany;
        this.isDefault = isDefault;
        this.deleted = false;
    }

    public void softDelete() {
        if (this.deleted) {
            throw new IllegalStateException("이미 삭제된 빌링키입니다.");
        }
        this.deleted = true;
        this.deletedAt = LocalDateTime.now();
    }

    public void setDefault(boolean isDefault) {
        this.isDefault = isDefault;
    }

    public String getDecryptedPgBillingKey() {
        return this.pgBillingKey;  // AES256Converter가 자동 복호화
    }
}
