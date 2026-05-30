package com.paycore.scheduler;

import com.paycore.payment.domain.Payment;
import com.paycore.payment.domain.PaymentLog;
import com.paycore.payment.method.PaymentMethodRouter;
import com.paycore.payment.method.cmd.PaymentDetail;
import com.paycore.payment.repository.PaymentRepository;
import com.paycore.payment.service.PaymentLogService;
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

    private final PaymentRepository paymentRepository;
    private final PaymentMethodRouter paymentMethodRouter;
    private final PaymentLogService paymentLogService;
    private final VirtualAccountRepository virtualAccountRepository;

    /**
     * 미완료 결제 복구: Payment 기반으로 txId로 실제 상태 재조회
     */
    @Transactional
    public boolean recoverPaymentWithTx(Payment payment) {
        log.info("[RecoveryService] 결제 복구 처리 - merchantOrderId: {}, txId: {}",
                payment.getMerchantOrderId(), payment.getTxId());

        PaymentDetail detail;
        try {
            detail = paymentMethodRouter.route(payment.getPaymentMethod())
                    .getPaymentByTxId(payment.getTxId());
        } catch (Exception e) {
            log.warn("[RecoveryService] 결제 조회 실패 - merchantOrderId: {}",
                    payment.getMerchantOrderId(), e);
            paymentLogService.saveLog(payment.getMerchantOrderId(), PaymentLog.LogType.SCHEDULER_RECOVERY,
                    null, null, false, "결제 조회 실패: " + e.getMessage());
            return false;
        }

        if (detail.isPaid()) {
            paymentLogService.saveLog(payment.getMerchantOrderId(), PaymentLog.LogType.SCHEDULER_RECOVERY,
                    null, detail, true, null);
            log.info("[RecoveryService] 결제 상태 확인 완료 (PAID) - merchantOrderId: {}",
                    payment.getMerchantOrderId());
            return true;
        } else {
            paymentLogService.saveLog(payment.getMerchantOrderId(), PaymentLog.LogType.SCHEDULER_RECOVERY,
                    null, detail, false, "결제 미완료 상태: " + detail.getStatus());
            return false;
        }
    }

    /**
     * 가상계좌 Webhook 누락 복구: txId로 입금 여부 직접 조회
     */
    @Transactional
    public void recoverVirtualAccountWithTx(
            com.paycore.virtualaccount.domain.VirtualAccount va,
            VirtualAccountService virtualAccountService) {

        if (va.isDeposited()) {
            log.debug("[RecoveryService] 가상계좌 이미 입금 완료 스킵 - merchantOrderId: {}",
                    va.getMerchantOrderId());
            return;
        }

        PaymentDetail detail;
        try {
            detail = paymentMethodRouter.route(com.paycore.payment.method.PaymentMethod.VIRTUAL_ACCOUNT)
                    .getPaymentByTxId(va.getTxId());
        } catch (Exception e) {
            log.warn("[RecoveryService] 가상계좌 조회 실패 - merchantOrderId: {}",
                    va.getMerchantOrderId(), e);
            return;
        }

        if (detail.isPaid()) {
            log.info("[RecoveryService] 가상계좌 입금 확인(복구) - merchantOrderId: {}",
                    va.getMerchantOrderId());
            virtualAccountService.processDeposit(va.getTxId());
            paymentLogService.saveLog(va.getMerchantOrderId(), PaymentLog.LogType.SCHEDULER_RECOVERY,
                    null, detail, true, "가상계좌 입금 복구");
        } else {
            log.debug("[RecoveryService] 가상계좌 미입금 - merchantOrderId: {}, 상태: {}",
                    va.getMerchantOrderId(), detail.getStatus());
        }
    }
}
