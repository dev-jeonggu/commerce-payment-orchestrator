package com.paycore.payment.client;

import com.paycore.common.exception.ErrorCode;
import com.paycore.common.exception.PaycoreException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * PortOne V1 Webhook 서명 검증기 (HMAC-SHA256)
 *
 * [PortOne V1 Webhook 서명 규격]
 * - 헤더: X-Imp-Signature
 * - 알고리즘: HMAC-SHA256(rawBody, imp_secret)
 * - 인코딩: 16진수(hex) 소문자
 *
 * [보안 원칙]
 * Webhook 수신 시 서명을 검증하지 않으면 외부에서 임의로 결제 완료 Webhook을 위조하여
 * 실제 결제 없이 Order.PAID 처리를 강제할 수 있음 (결제 위변조 취약점).
 *
 * [타이밍 어택 방지]
 * MessageDigest.isEqual()로 상수 시간(constant-time) 비교 수행.
 * String.equals()는 첫 글자 불일치 시 즉시 false를 반환하므로 타이밍 어택에 취약함.
 *
 * [개발/테스트 환경]
 * imp-secret이 "test_imp_secret"이면 서명 검증을 스킵하여 로컬 테스트 편의성 보장.
 * 운영 환경에서는 반드시 실제 imp-secret을 환경변수로 주입해야 함.
 */
@Slf4j
@Component
public class PortOneWebhookVerifier {

    private static final String TEST_SECRET = "test_imp_secret";

    @Value("${portone.imp-secret}")
    private String impSecret;

    /**
     * Webhook X-Imp-Signature 헤더 서명 검증
     *
     * @param signature X-Imp-Signature 헤더 값
     * @param rawBody   요청 원문 (JSON 문자열)
     * @throws PaycoreException WEBHOOK_SIGNATURE_INVALID - 서명 없음 또는 불일치
     */
    public void verify(String signature, String rawBody) {
        // 개발/테스트 환경에서는 서명 검증 스킵 (imp-secret이 기본값일 때)
        if (TEST_SECRET.equals(impSecret)) {
            log.debug("[WebhookVerifier] 테스트 환경 - 서명 검증 스킵");
            return;
        }

        if (signature == null || signature.isBlank()) {
            log.warn("[WebhookVerifier] X-Imp-Signature 헤더 없음 - Webhook 위조 가능성");
            throw new PaycoreException(ErrorCode.WEBHOOK_SIGNATURE_INVALID,
                    "Webhook 서명(X-Imp-Signature) 헤더가 없습니다.");
        }

        String expected = hmacSha256Hex(rawBody, impSecret);

        // 타이밍 어택 방지: MessageDigest.isEqual()은 상수 시간 비교
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.toLowerCase().getBytes(StandardCharsets.UTF_8))) {
            log.warn("[WebhookVerifier] Webhook 서명 불일치 - 위조된 요청 차단");
            throw new PaycoreException(ErrorCode.WEBHOOK_SIGNATURE_INVALID,
                    "Webhook 서명이 유효하지 않습니다.");
        }

        log.debug("[WebhookVerifier] Webhook 서명 검증 성공");
    }

    private String hmacSha256Hex(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                    key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            log.error("[WebhookVerifier] HMAC-SHA256 계산 실패", e);
            throw new PaycoreException(ErrorCode.INTERNAL_SERVER_ERROR, "Webhook 서명 계산 실패");
        }
    }
}
