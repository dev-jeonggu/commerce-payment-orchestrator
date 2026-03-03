package com.paycore.payment.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 재고 관리 서비스
 * 실무에서는 별도 마이크로서비스 또는 재고 DB 처리
 * Saga 패턴 테스트를 위해 주입 가능한 Spring Bean으로 설계
 */
@Slf4j
@Service
public class InventoryService {

    public void decrease(Long itemId, int quantity) {
        log.info("[InventoryService] 재고 차감 - itemId: {}, quantity: {}", itemId, quantity);
        // 실무: 재고 DB/Redis에서 차감 + 부족 시 InsufficientStockException 발생
    }
}
