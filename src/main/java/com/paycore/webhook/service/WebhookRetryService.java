package com.paycore.webhook.service;

import com.paycore.notification.AlertService;
import com.paycore.webhook.domain.WebhookDeadLetter;
import com.paycore.webhook.domain.WebhookDeadLetterStatus;
import com.paycore.webhook.repository.WebhookDeadLetterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.HexFormat;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookRetryService {

    private final WebhookDeadLetterRepository deadLetterRepository;
    private final AlertService alertService;
    private final WebClient.Builder webClientBuilder;

    /**
     * DLQ 저장 — WebhookDispatcher 실패 콜백에서 호출.
     *
     * REQUIRES_NEW: @Async 컨텍스트에서 호출되므로 트랜잭션 독립 보장.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveToDlq(String merchantId, String txId, String merchantOrderId,
                          String webhookUrl, String webhookSecret,
                          String payload, String errorMessage) {
        WebhookDeadLetter deadLetter = WebhookDeadLetter.builder()
                .merchantId(merchantId)
                .txId(txId)
                .merchantOrderId(merchantOrderId)
                .webhookUrl(webhookUrl)
                .webhookSecret(webhookSecret)
                .payload(payload)
                .lastErrorMessage(errorMessage)
                .build();
        deadLetterRepository.save(deadLetter);

        log.error("[WebhookDLQ] Webhook 발송 실패 저장 - merchantId: {}, txId: {}", merchantId, txId);
        alertService.sendWarning(
                "Webhook 발송 실패 DLQ 저장",
                merchantOrderId != null ? merchantOrderId : txId,
                "url=" + webhookUrl + ", error=" + errorMessage
        );
    }

    /**
     * DLQ 재시도 — 스케줄러에서 호출.
     *
     * [트랜잭션 분리 설계]
     * HTTP 호출을 @Transactional 안에서 .block()하면 DB 커넥션을 점유한 채로 대기한다.
     * 이를 방지하기 위해:
     *   1. markProcessing() 커밋 (TX1)
     *   2. HTTP 호출 (트랜잭션 없음)
     *   3. 결과에 따라 markResolved/markFailed 커밋 (TX2)
     */
    public void retry(WebhookDeadLetter deadLetter) {
        markProcessing(deadLetter);

        boolean success = false;
        String errorMessage = null;
        try {
            String signature = hmacSha256(deadLetter.getWebhookSecret(), deadLetter.getPayload());

            webClientBuilder.build()
                    .post()
                    .uri(deadLetter.getWebhookUrl())
                    .header("X-Paycore-Signature", signature)
                    .header("Content-Type", "application/json")
                    .bodyValue(deadLetter.getPayload())
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(Duration.ofSeconds(10))
                    .block();

            success = true;
            log.info("[WebhookDLQ] 재시도 성공 - merchantId: {}, txId: {}",
                    deadLetter.getMerchantId(), deadLetter.getTxId());

        } catch (Exception e) {
            errorMessage = e.getMessage();
            log.warn("[WebhookDLQ] 재시도 실패 - merchantId: {}, txId: {}",
                    deadLetter.getMerchantId(), deadLetter.getTxId(), e);
        }

        updateResult(deadLetter, success, errorMessage);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markProcessing(WebhookDeadLetter deadLetter) {
        deadLetter.markProcessing();
        deadLetterRepository.save(deadLetter);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateResult(WebhookDeadLetter deadLetter, boolean success, String errorMessage) {
        if (success) {
            deadLetter.markResolved();
        } else {
            deadLetter.markFailed(errorMessage);
            if (deadLetter.isExhausted()) {
                log.error("[WebhookDLQ] 최대 재시도 초과 - merchantId: {} (수동 처리 필요)",
                        deadLetter.getMerchantId());
                alertService.sendCritical(
                        "Webhook DLQ 최대 재시도 초과 - 수동 처리 필요",
                        deadLetter.getMerchantOrderId(),
                        "merchantId=" + deadLetter.getMerchantId()
                                + ", url=" + deadLetter.getWebhookUrl()
                                + ", error=" + errorMessage
                );
            } else {
                log.warn("[WebhookDLQ] 재시도 실패 ({}회) - merchantId: {}, txId: {}",
                        deadLetter.getAttemptCount(), deadLetter.getMerchantId(), deadLetter.getTxId());
            }
        }
        deadLetterRepository.save(deadLetter);
    }

    public List<WebhookDeadLetter> findPending(Pageable pageable) {
        return deadLetterRepository.findByStatus(WebhookDeadLetterStatus.PENDING, pageable).getContent();
    }

    private String hmacSha256(String secret, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }
}
