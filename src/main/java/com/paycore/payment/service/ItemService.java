package com.paycore.payment.service;

import com.paycore.common.exception.ErrorCode;
import com.paycore.common.exception.PaycoreException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 상품 가격 조회 서비스
 * 실무에서는 상품 카탈로그 DB 또는 외부 마이크로서비스에서 조회
 * 주문 생성 시 클라이언트 금액이 아닌 서버 기준 가격으로 검증하기 위해 사용
 */
@Slf4j
@Service
public class ItemService {

    private static final Map<Long, Long> ITEM_PRICE_TABLE = Map.of(
        1L,  10_000L,
        2L,  20_000L,
        3L,  50_000L,
        10L, 30_000L
    );

    public Long getPrice(Long itemId) {
        Long price = ITEM_PRICE_TABLE.get(itemId);
        if (price == null) {
            throw new PaycoreException(ErrorCode.ITEM_NOT_FOUND,
                    "존재하지 않는 상품입니다. itemId: " + itemId);
        }
        log.info("[ItemService] 상품 가격 조회 - itemId: {}, price: {}", itemId, price);
        return price;
    }
}
