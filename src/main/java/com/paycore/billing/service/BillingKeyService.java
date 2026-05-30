package com.paycore.billing.service;

import com.paycore.billing.controller.dto.BillingKeyChargeRequest;
import com.paycore.billing.controller.dto.BillingKeyChargeResponse;
import com.paycore.billing.controller.dto.BillingKeyRegisterRequest;
import com.paycore.billing.controller.dto.BillingKeyResponse;
import com.paycore.billing.domain.BillingKey;
import com.paycore.billing.repository.BillingKeyRepository;
import com.paycore.lock.DistributedLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 빌링키 서비스
 *
 * charge/delete는 분산 락 → BillingKeyProcessor(별도 Bean)로 위임.
 * 람다 내부에서 같은 클래스 메서드를 호출하면 self-invocation으로 @Transactional이
 * 무시되기 때문에 @Transactional 로직은 BillingKeyProcessor에 위치한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingKeyService {

    private final BillingKeyRepository billingKeyRepository;
    private final BillingKeyProcessor billingKeyProcessor;
    private final DistributedLockService distributedLockService;

    public BillingKeyResponse register(BillingKeyRegisterRequest request) {
        // isDefault 등록 시 기존 default 해제 + 신규 저장을 원자적으로 처리.
        // BillingKeyProcessor.register()로 위임해 @Transactional이 Spring 프록시를 통해 적용되도록 함.
        return distributedLockService.executeWithBillingKeyRegisterLock(
                request.getMerchantId(), request.getUserId(),
                () -> billingKeyProcessor.register(request));
    }

    @Transactional(readOnly = true)
    public List<BillingKeyResponse> getList(Long userId) {
        return billingKeyRepository
                .findByUserIdAndDeletedFalseOrderByIsDefaultDescCreatedAtDesc(userId)
                .stream()
                .map(BillingKeyResponse::of)
                .collect(Collectors.toList());
    }

    /**
     * 빌링키 결제 (자동결제/정기결제)
     *
     * 분산 락으로 delete와 charge 동시 실행을 직렬화한 뒤
     * BillingKeyProcessor.charge()로 위임 (Spring 프록시를 통해 @Transactional 보장).
     */
    public BillingKeyChargeResponse charge(BillingKeyChargeRequest request) {
        return distributedLockService.executeWithBillingKeyLock(
                request.getBillingKeyId(),
                () -> billingKeyProcessor.charge(request));
    }

    public void delete(Long billingKeyId, Long userId) {
        distributedLockService.executeWithBillingKeyLock(billingKeyId, () -> {
            billingKeyProcessor.delete(billingKeyId, userId);
            return null;
        });
    }

}
