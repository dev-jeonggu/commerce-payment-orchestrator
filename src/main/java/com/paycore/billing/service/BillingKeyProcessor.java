package com.paycore.billing.service;

import com.paycore.billing.controller.dto.BillingKeyChargeRequest;
import com.paycore.billing.controller.dto.BillingKeyChargeResponse;
import com.paycore.billing.domain.BillingKey;
import com.paycore.billing.repository.BillingKeyRepository;
import com.paycore.common.exception.ErrorCode;
import com.paycore.common.exception.PaycoreException;
import com.paycore.merchant.domain.Merchant;
import com.paycore.merchant.service.MerchantService;
import com.paycore.payment.domain.Payment;
import com.paycore.payment.method.PaymentMethod;
import com.paycore.payment.method.PaymentMethodRouter;
import com.paycore.payment.method.cmd.BillingCommand;
import com.paycore.payment.method.cmd.BillingResult;
import com.paycore.payment.repository.PaymentRepository;
import com.paycore.webhook.WebhookDispatcher;
import com.paycore.webhook.dto.WebhookPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 빌링키 트랜잭션 처리기
 *
 * BillingKeyService가 분산 락 내부에서 람다로 doCharge/doDelete를 호출하면
 * self-invocation으로 @Transactional이 무시된다.
 * 이를 방지하기 위해 @Transactional 메서드를 별도 Bean으로 분리.
 * (VirtualAccountExpiryProcessor와 동일한 패턴)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BillingKeyProcessor {

    private final BillingKeyRepository billingKeyRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentMethodRouter paymentMethodRouter;
    private final MerchantService merchantService;
    private final WebhookDispatcher webhookDispatcher;

    @Transactional
    public BillingKeyChargeResponse charge(BillingKeyChargeRequest request) {
        Merchant merchant = merchantService.getMerchantOrThrow(request.getMerchantId());

        if (paymentRepository.existsByMerchantOrderId(request.getMerchantOrderId())) {
            throw new PaycoreException(ErrorCode.PAYMENT_ALREADY_PROCESSED,
                    "해당 주문에 이미 결제 내역이 존재합니다. merchantOrderId: " + request.getMerchantOrderId());
        }

        BillingKey billingKey = billingKeyRepository
                .findByIdAndUserIdAndDeletedFalse(request.getBillingKeyId(), request.getUserId())
                .orElseThrow(() -> new PaycoreException(ErrorCode.BILLING_KEY_NOT_FOUND));

        BillingCommand command = BillingCommand.builder()
                .pgBillingKey(billingKey.getDecryptedPgBillingKey())
                .orderId(request.getMerchantOrderId())
                .amount(request.getAmount())
                .orderName(request.getOrderName())
                .build();

        log.info("[BillingKeyProcessor] 빌링키 결제 시작 - merchantId: {}, merchantOrderId: {}, amount: {}",
                request.getMerchantId(), request.getMerchantOrderId(), request.getAmount());

        BillingResult result = paymentMethodRouter.route(PaymentMethod.CARD).chargeBilling(command);

        if (!"paid".equals(result.getStatus())) {
            throw new PaycoreException(ErrorCode.BILLING_CHARGE_FAILED,
                    "빌링키 결제가 완료되지 않았습니다. 상태: " + result.getStatus());
        }

        Payment payment = Payment.builder()
                .merchantId(request.getMerchantId())
                .txId(result.getPaymentKey())
                .merchantOrderId(request.getMerchantOrderId())
                .paymentMethod(PaymentMethod.CARD)
                .paidAmount(request.getAmount())
                .build();
        paymentRepository.save(payment);

        log.info("[BillingKeyProcessor] 빌링키 결제 완료 - merchantOrderId: {}, txId: {}",
                request.getMerchantOrderId(), result.getPaymentKey());

        dispatchWebhook(merchant, payment);

        return BillingKeyChargeResponse.of(payment);
    }

    @Transactional
    public void delete(Long billingKeyId, Long userId) {
        BillingKey billingKey = billingKeyRepository
                .findByIdAndUserIdAndDeletedFalse(billingKeyId, userId)
                .orElseThrow(() -> new PaycoreException(ErrorCode.BILLING_KEY_NOT_FOUND));
        billingKey.softDelete();
        log.info("[BillingKeyProcessor] 빌링키 삭제 - id: {}, userId: {}", billingKeyId, userId);
    }

    private void dispatchWebhook(Merchant merchant, Payment payment) {
        try {
            WebhookPayload payload = WebhookPayload.builder()
                    .txId(payment.getTxId())
                    .merchantOrderId(payment.getMerchantOrderId())
                    .status("paid")
                    .amount(payment.getPaidAmount())
                    .paymentMethod(payment.getPaymentMethod().name())
                    .paidAt(LocalDateTime.now())
                    .build();
            webhookDispatcher.dispatch(merchant, payload);
        } catch (Exception e) {
            log.error("[BillingKeyProcessor] Webhook 발송 실패 - merchantOrderId: {} (수동 확인 필요)",
                    payment.getMerchantOrderId(), e);
        }
    }
}
