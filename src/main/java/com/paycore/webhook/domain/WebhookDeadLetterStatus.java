package com.paycore.webhook.domain;

public enum WebhookDeadLetterStatus {
    PENDING,     // 재시도 대기
    PROCESSING,  // 재시도 처리 중
    RESOLVED,    // 발송 성공
    EXHAUSTED    // 최대 재시도 초과 → 수동 처리 필요
}
