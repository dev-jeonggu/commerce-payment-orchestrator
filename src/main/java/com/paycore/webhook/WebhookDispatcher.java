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
import reactor.core.scheduler.Schedulers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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

        String signature;
        try {
            signature = hmacSha256(merchant.getWebhookSecret(), body);
        } catch (Exception e) {
            log.error("[WebhookDispatcher] HMAC 서명 실패 - merchantId: {}", merchant.getMerchantId(), e);
            return;
        }

        // publishOn(boundedElastic): HTTP 응답 수신 후 콜백을 boundedElastic 스레드로 전환.
        // subscribe()의 성공/실패 콜백은 기본적으로 Netty IO 스레드에서 실행되는데,
        // 에러 콜백의 saveToDlq()는 JDBC 블로킹 호출이므로 Netty IO 스레드를 점유하면
        // Reactor 이벤트 루프가 멈춘다. boundedElastic은 블로킹 IO에 적합한 스레드풀.
        webClientBuilder.build()
                .post()
                .uri(merchant.getWebhookUrl())
                .header("X-Paycore-Signature", signature)
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(10))
                .publishOn(Schedulers.boundedElastic())
                .subscribe(
                        __ -> log.info("[WebhookDispatcher] 발송 완료 - merchantId: {}, txId: {}",
                                merchant.getMerchantId(), payload.getTxId()),
                        e -> {
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
                );
    }

    private String hmacSha256(String secret, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }
}
