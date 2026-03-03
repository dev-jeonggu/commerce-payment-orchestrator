package com.paycore.payment.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 포인트 관리 서비스
 * 실무에서는 포인트 DB 처리
 * Saga 패턴 테스트를 위해 주입 가능한 Spring Bean으로 설계
 */
@Slf4j
@Service
public class PointService {

    public void earn(Long userId, Long amount) {
        log.info("[PointService] 포인트 적립 - userId: {}, amount: {}", userId, amount);
        // 실무: 포인트 DB 적립 (결제금액의 1% 등)
    }
}
