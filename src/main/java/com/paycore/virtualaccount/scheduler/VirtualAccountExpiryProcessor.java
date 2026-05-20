package com.paycore.virtualaccount.scheduler;

import com.paycore.common.exception.ErrorCode;
import com.paycore.common.exception.PaycoreException;
import com.paycore.order.domain.Order;
import com.paycore.order.repository.OrderRepository;
import com.paycore.virtualaccount.domain.VirtualAccount;
import com.paycore.virtualaccount.repository.VirtualAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 가상계좌 만료 처리 - 건별 독립 트랜잭션
 *
 * [분리 이유]
 * 스케줄러 메서드에서 직접 @Transactional을 걸면 전체가 하나의 TX.
 * 하나 실패 시 전체 롤백 → 나머지 만료 건 누락.
 *
 * 별도 @Component로 분리 + REQUIRES_NEW:
 * 각 가상계좌를 독립 TX로 처리 → 하나 실패해도 나머지 정상 처리.
 *
 * [주의] 자기 호출(self-invocation) 문제:
 * 스케줄러 내부에서 this.expire() 호출 시 프록시 우회 → @Transactional 무시.
 * 반드시 별도 빈(VirtualAccountExpiryProcessor)으로 분리해야 함.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VirtualAccountExpiryProcessor {

    private final VirtualAccountRepository virtualAccountRepository;
    private final OrderRepository orderRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expire(Long virtualAccountId) {
        VirtualAccount va = virtualAccountRepository.findById(virtualAccountId)
                .orElseThrow(() -> new PaycoreException(ErrorCode.VIRTUAL_ACCOUNT_NOT_FOUND));

        // 스케줄러 재실행 시 이미 처리된 건 skip (멱등성)
        if (!va.isIssued()) {
            log.debug("[VA-ExpiryProcessor] 이미 처리된 가상계좌 스킵 - vaId: {}, status: {}",
                    virtualAccountId, va.getStatus());
            return;
        }

        va.markAsExpired();

        // 주문 상태도 CANCELLED로 변경
        orderRepository.findByOrderNo(va.getOrderNo()).ifPresentOrElse(
                order -> {
                    if (!order.isPaid()) {
                        order.markAsCancelled();
                        log.info("[VA-ExpiryProcessor] 가상계좌 만료 처리 완료 - orderNo: {}, vaId: {}",
                                va.getOrderNo(), virtualAccountId);
                    } else {
                        // 이미 PAID인 주문: 만료 스케줄러가 늦게 실행된 케이스 (입금은 정상 완료)
                        log.warn("[VA-ExpiryProcessor] 이미 결제 완료된 주문의 VA 만료 시도 스킵 - orderNo: {}",
                                va.getOrderNo());
                    }
                },
                () -> log.error("[VA-ExpiryProcessor] 연결된 주문을 찾을 수 없음 - orderNo: {}",
                        va.getOrderNo())
        );
    }
}
