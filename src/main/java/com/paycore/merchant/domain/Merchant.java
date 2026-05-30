package com.paycore.merchant.domain;

import com.paycore.billing.crypto.AES256Converter;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "merchants")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class Merchant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String merchantId;

    @Convert(converter = AES256Converter.class)
    @Column(nullable = false)
    private String secretKey;

    @Column(nullable = false)
    private String webhookUrl;

    @Convert(converter = AES256Converter.class)
    @Column(nullable = false)
    private String webhookSecret;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MerchantStatus status;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public Merchant(String merchantId, String secretKey, String webhookUrl, String webhookSecret) {
        this.merchantId = merchantId;
        this.secretKey = secretKey;
        this.webhookUrl = webhookUrl;
        this.webhookSecret = webhookSecret;
        this.status = MerchantStatus.ACTIVE;
    }
}
