package com.paycore.webhook.domain;

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
 * Webhook 발송 실패 Dead Letter
 *
 * WebhookDispatcher 발송 실패 시 저장 → WebhookRetryScheduler가 1분마다 재시도.
 * 최대 5회 실패 시 EXHAUSTED → 운영자 알람 + 수동 처리.
 *
 * [webhookUrl / webhookSecret 보관 이유]
 * 재시도 시점에 Merchant 정보가 변경될 수 있으므로 발송 당시의 값을 스냅샷으로 저장.
 */
@Entity
@Table(name = "webhook_dead_letters",
        indexes = {
                @Index(name = "idx_wdl_status_created", columnList = "status, created_at")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class WebhookDeadLetter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "tx_id")
    private String txId;

    @Column(name = "merchant_order_id")
    private String merchantOrderId;

    @Column(name = "webhook_url", nullable = false, length = 500)
    private String webhookUrl;

    @Convert(converter = AES256Converter.class)
    @Column(name = "webhook_secret", nullable = false, length = 500)
    private String webhookSecret;

    /** 발송할 JSON 페이로드 원문 */
    @Column(name = "payload", columnDefinition = "TEXT", nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WebhookDeadLetterStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_error_message", length = 1000)
    private String lastErrorMessage;

    @Column(name = "last_attempted_at")
    private LocalDateTime lastAttemptedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private static final int MAX_ATTEMPTS = 5;

    @Builder
    public WebhookDeadLetter(String merchantId, String txId, String merchantOrderId,
                              String webhookUrl, String webhookSecret,
                              String payload, String lastErrorMessage) {
        this.merchantId = merchantId;
        this.txId = txId;
        this.merchantOrderId = merchantOrderId;
        this.webhookUrl = webhookUrl;
        this.webhookSecret = webhookSecret;
        this.payload = payload;
        this.lastErrorMessage = lastErrorMessage;
        this.status = WebhookDeadLetterStatus.PENDING;
        this.attemptCount = 0;
    }

    public void markProcessing() {
        this.status = WebhookDeadLetterStatus.PROCESSING;
        this.lastAttemptedAt = LocalDateTime.now();
    }

    public void markResolved() {
        this.status = WebhookDeadLetterStatus.RESOLVED;
    }

    public void markFailed(String error) {
        this.attemptCount++;
        this.lastErrorMessage = error;
        this.status = this.attemptCount >= MAX_ATTEMPTS
                ? WebhookDeadLetterStatus.EXHAUSTED
                : WebhookDeadLetterStatus.PENDING;
    }

    public boolean isExhausted() {
        return this.status == WebhookDeadLetterStatus.EXHAUSTED;
    }
}
