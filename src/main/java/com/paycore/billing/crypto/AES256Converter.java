package com.paycore.billing.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-256-CBC 기반 JPA AttributeConverter
 *
 * [알고리즘 선택 이유]
 * - AES-256-CBC: 검증된 표준, 구현 단순, PKCS5Padding으로 블록 크기 처리
 * - IV(초기화 벡터): 매 암호화마다 랜덤 생성 → 동일한 평문도 다른 암호문
 * - 저장 형식: Base64(IV 16바이트 + 암호문)
 *
 * [Spring 통합] AES256KeyInitializer가 @PostConstruct에서 키 주입
 * JPA는 AttributeConverter를 Bean이 아닌 클래스로 직접 인스턴스화하므로
 * static 변수 패턴 사용. (Spring Boot 3.x에서 @Component로 주입 가능하나 Hibernate 초기화 순서 이슈 있음)
 */
@Slf4j
@Converter
public class AES256Converter implements AttributeConverter<String, String> {

    private static volatile String SECRET_KEY;

    static void setKey(String key) {
        SECRET_KEY = key;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) return null;
        if (SECRET_KEY == null) throw new IllegalStateException("AES 암호화 키 미초기화");

        try {
            byte[] keyBytes = Base64.getDecoder().decode(SECRET_KEY);
            SecretKeySpec secretKeySpec = new SecretKeySpec(keyBytes, "AES");

            byte[] iv = new byte[16];
            new SecureRandom().nextBytes(iv);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivSpec);
            byte[] encrypted = cipher.doFinal(attribute.getBytes(StandardCharsets.UTF_8));

            // IV + 암호문 결합 후 Base64 인코딩
            byte[] combined = new byte[16 + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, 16);
            System.arraycopy(encrypted, 0, combined, 16, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);

        } catch (Exception e) {
            throw new IllegalStateException("빌링키 암호화 실패", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        if (SECRET_KEY == null) throw new IllegalStateException("AES 암호화 키 미초기화");

        try {
            byte[] keyBytes = Base64.getDecoder().decode(SECRET_KEY);
            SecretKeySpec secretKeySpec = new SecretKeySpec(keyBytes, "AES");

            byte[] combined = Base64.getDecoder().decode(dbData);
            byte[] iv = Arrays.copyOfRange(combined, 0, 16);
            byte[] encrypted = Arrays.copyOfRange(combined, 16, combined.length);

            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivSpec);
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);

        } catch (Exception e) {
            throw new IllegalStateException("빌링키 복호화 실패", e);
        }
    }
}
