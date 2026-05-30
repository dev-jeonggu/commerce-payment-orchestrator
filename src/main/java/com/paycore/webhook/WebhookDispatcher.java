package com.paycore.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paycore.merchant.domain.Merchant;
import com.paycore.webhook.dto.WebhookPayload;
import com.paycore.webhook.service.WebhookRetryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.HexFormat;

/**
 * 가맹점 Webhook 발송기
 *
 * [발송 흐름]
 * 1. 페이로드 JSON 직렬화
 * 2. HMAC-SHA256(webhookSecret, body) → X-Paycore-Signature 헤더
 * 3. 가맹점 webhookUrl로 POST (비동기 @Async)
 * 4. 실패 시 WebhookRetryService.saveToDlq() → 재시도 스케줄러로 위임
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookDispatcher {

    private final ObjectMapper objectMapper;
    private final WebClient.Builder webClientBuilder;
    private final WebhookRetryService webhookRetryService;

    @Async
    public void dispatch(Merchant merchant, WebhookPayload payload) {
        String body;
        try {
            body = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.error("[WebhookDispatcher] 페이로드 직렬화 실패 - merchantId: {}",
                    merchant.getMerchantId(), e);
            return;
        }

        try {
            String signature = hmacSha256(merchant.getWebhookSecret(), body);

            webClientBuilder.build()
                    .post()
                    .uri(merchant.getWebhookUrl())
                    .header("X-Paycore-Signature", signature)
                    .header("Content-Type", "application/json")
                    .bodyValue(body)
                    .retrieve()
                    .toBodilessEntity()
                    .block();

            log.info("[WebhookDispatcher] 발송 완료 - merchantId: {}, txId: {}",
                    merchant.getMerchantId(), payload.getTxId());

        } catch (Exception e) {
            log.error("[WebhookDispatcher] 발송 실패 - merchantId: {}, txId: {} → DLQ 저장",
                    merchant.getMerchantId(), payload.getTxId(), e);

            webhookRetryService.saveToDlq(
                    merchant.getMerchantId(),
                    payload.getTxId(),
                    payload.getMerchantOrderId(),
                    merchant.getWebhookUrl(),
                    merchant.getWebhookSecret(),
                    body,
                    e.getMessage()
            );
        }
    }

    private String hmacSha256(String secret, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes()));
    }
}
