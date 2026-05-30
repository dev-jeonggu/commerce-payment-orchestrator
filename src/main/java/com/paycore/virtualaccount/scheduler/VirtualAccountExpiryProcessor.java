package com.paycore.virtualaccount.scheduler;

import com.paycore.common.exception.ErrorCode;
import com.paycore.common.exception.PaycoreException;
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
 * [분리 이유] 스케줄러 전체가 하나의 TX이면 하나 실패 시 전체 롤백 → 나머지 만료 건 누락.
 * 별도 @Component로 분리 + REQUIRES_NEW로 각 건을 독립 TX로 처리.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VirtualAccountExpiryProcessor {

    private final VirtualAccountRepository virtualAccountRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expire(Long virtualAccountId) {
        VirtualAccount va = virtualAccountRepository.findById(virtualAccountId)
                .orElseThrow(() -> new PaycoreException(ErrorCode.VIRTUAL_ACCOUNT_NOT_FOUND));

        if (!va.isIssued()) {
            log.debug("[VA-ExpiryProcessor] 이미 처리된 가상계좌 스킵 - vaId: {}, status: {}",
                    virtualAccountId, va.getStatus());
            return;
        }

        va.markAsExpired();
        log.info("[VA-ExpiryProcessor] 가상계좌 만료 처리 완료 - merchantOrderId: {}, vaId: {}",
                va.getMerchantOrderId(), virtualAccountId);
    }
}
