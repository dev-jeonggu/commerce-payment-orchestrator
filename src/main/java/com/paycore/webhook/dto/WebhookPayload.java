package com.paycore.webhook.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class WebhookPayload {
    private String txId;
    private String merchantOrderId;
    private String status;  // paid, cancelled, failed
    private Long amount;
    private String paymentMethod;
    private LocalDateTime paidAt;
}
