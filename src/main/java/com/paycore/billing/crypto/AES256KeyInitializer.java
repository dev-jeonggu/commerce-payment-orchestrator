package com.paycore.billing.crypto;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Base64;

/**
 * AES-256 암호화 키 초기화 컴포넌트
 *
 * Spring Bean으로 관리되어 @Value 주입 가능.
 * @PostConstruct에서 AES256Converter의 static 키를 설정.
 *
 * [보안] 운영 환경에서 ENCRYPTION_AES_KEY는 반드시 환경변수 또는 Secrets Manager로 관리.
 * application-prod.yml에 평문 키 절대 작성 금지.
 *
 * [키 길이 검증] AES-256 = 32바이트 키 필요.
 * Base64 인코딩된 32바이트 = 44자 문자열.
 */
@Slf4j
@Component
public class AES256KeyInitializer {

    @Value("${encryption.aes-key}")
    private String aesKeyBase64;

    @PostConstruct
    public void init() {
        byte[] keyBytes = Base64.getDecoder().decode(aesKeyBase64);
        if (keyBytes.length != 32) {
            throw new IllegalStateException(
                    "AES-256 키는 32바이트(Base64: 44자)여야 합니다. 현재: " + keyBytes.length + "바이트");
        }
        AES256Converter.setKey(aesKeyBase64);
        log.info("[AES256KeyInitializer] 암호화 키 초기화 완료 (keyLength=32bytes)");
    }
}
