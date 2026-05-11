package com.paycore.common.util;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Component
public class OrderNumberGenerator {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 주문번호 생성: ORD-20260303-A1B2C3D4 형태
     * UUID 앞 8자리 사용 → 멀티 인스턴스 환경에서 중복 없음
     */
    public String generate() {
        String date = LocalDate.now().format(DATE_FORMAT);
        String unique = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        return String.format("ORD-%s-%s", date, unique);
    }
}
