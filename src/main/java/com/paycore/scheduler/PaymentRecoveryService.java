package com.paycore.scheduler;

import com.paycore.payment.domain.PaymentLog;
import com.paycore.payment.method.PaymentMethodRouter;
import com.paycore.payment.method.cmd.PaymentDetail;
import com.paycore.payment.service.PaymentLogService;
import com.paycore.virtualaccount.domain.VirtualAccount;
import com.paycore.virtualaccount.domain.VirtualAccountStatus;
import com.paycore.virtualaccount.repository.VirtualAccountRepository;
import com.paycore.virtualaccount.service.VirtualAccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 스케줄러 복구 트랜잭션 서비스
 *
 * [설계 이유] PaymentRecoveryScheduler와 분리한 핵심 이유:
 * Spring AOP 트랜잭션은 프록시를 통해서만 동작.
 * self-invocation 시 @Transactional 무시 → 별도 Bean으로 분리.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentRecoveryService {

    private final PaymentMethodRouter paymentMethodRouter;
    private final PaymentLogService paymentLogService;
    private final VirtualAccountRepository virtualAccountRepository;

    /**
     * 가상계좌 Webhook 누락 복구
     *
     * txId만 받아 이 메서드 내에서 DB를 fresh하게 재조회한다.
     * 스케줄러에서 로드한 VirtualAccount 엔티티를 그대로 전달하면
     * detached 엔티티의 stale 상태로 isDeposited() 판단이 틀릴 수 있다.
     */
    @Transactional
    public void recoverVirtualAccountWithTx(String txId, VirtualAccountService virtualAccountService) {
        // detached 엔티티 대신 fresh DB 조회로 현재 상태를 정확히 확인
        VirtualAccount fresh = virtualAccountRepository.findByTxId(txId).orElse(null);
        if (fresh == null) {
            log.warn("[RecoveryService] 가상계좌 없음 - txId: {}", txId);
            return;
        }

        if (fresh.getStatus() == VirtualAccountStatus.DEPOSITED) {
            log.debug("[RecoveryService] 가상계좌 이미 입금 완료 스킵 - merchantOrderId: {}",
                    fresh.getMerchantOrderId());
            return;
        }

        if (fresh.getStatus() == VirtualAccountStatus.EXPIRED) {
            log.debug("[RecoveryService] 만료된 가상계좌 복구 스킵 - merchantOrderId: {}",
                    fresh.getMerchantOrderId());
            return;
        }

        PaymentDetail detail;
        try {
            detail = paymentMethodRouter
                    .route(com.paycore.payment.method.PaymentMethod.VIRTUAL_ACCOUNT)
                    .getPaymentByTxId(txId);
        } catch (Exception e) {
            log.warn("[RecoveryService] 가상계좌 조회 실패 - merchantOrderId: {}",
                    fresh.getMerchantOrderId(), e);
            return;
        }

        if (detail.isPaid()) {
            log.info("[RecoveryService] 가상계좌 입금 확인(복구) - merchantOrderId: {}",
                    fresh.getMerchantOrderId());
            virtualAccountService.processDeposit(txId);
            paymentLogService.saveLog(fresh.getMerchantOrderId(), PaymentLog.LogType.SCHEDULER_RECOVERY,
                    null, detail, true, "가상계좌 입금 복구");
        } else {
            log.debug("[RecoveryService] 가상계좌 미입금 - merchantOrderId: {}, 상태: {}",
                    fresh.getMerchantOrderId(), detail.getStatus());
        }
    }
}
