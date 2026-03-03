package com.paycore.common.util;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class OrderNumberGenerator {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private final AtomicInteger sequence = new AtomicInteger(0);

    /**
     * 주문번호 생성: ORD-20260303-001 형태
     * 실무에서는 Redis incr 또는 DB sequence를 활용하는 것이 권장됨
     */
    public String generate() {
        String date = LocalDate.now().format(DATE_FORMAT);
        int seq = sequence.incrementAndGet() % 1000;
        return String.format("ORD-%s-%03d", date, seq);
    }
}
