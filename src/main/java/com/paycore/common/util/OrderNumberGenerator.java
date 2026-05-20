package com.paycore.common.util;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Component
public class OrderNumberGenerator {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 주문번호 생성: ORD-20260303-A1B2C3D4E5F6A1B2 형태 (16자리 hex)
     *
     * [변경 이유] 기존 8자리(32비트) UUID는 생일 문제로 ~77,000건에서 50% 충돌 발생.
     *   → 일 주문 1만 건 기준 약 8일 내 50% 확률 중복 주문번호 발생.
     *   → 16자리(64비트)로 변경 시 50% 충돌 발생 기준: 약 36억 건 (실질적 중복 불가).
     *
     * [포맷] "ORD-{yyyyMMdd}-{UUID 앞 16자리 대문자 hex}"
     *   전체 길이: 3 + 1 + 8 + 1 + 16 = 29자 (DB VARCHAR(40) 이하로 여유)
     *
     * [멀티 인스턴스] UUID는 JVM 내 Random 기반이므로 인스턴스 간 중복 없음.
     *   단, 극히 낮은 확률의 충돌 방어를 위해 OrderService에서
     *   DataIntegrityViolationException catch → 1회 재시도 로직 유지.
     */
    public String generate() {
        String date = LocalDate.now().format(DATE_FORMAT);
        String unique = UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        return String.format("ORD-%s-%s", date, unique);
    }
}
