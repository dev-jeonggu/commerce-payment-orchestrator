package com.paycore.billing.service;

import com.paycore.billing.controller.dto.BillingKeyChargeRequest;
import com.paycore.billing.controller.dto.BillingKeyChargeResponse;
import com.paycore.billing.controller.dto.BillingKeyRegisterRequest;
import com.paycore.billing.controller.dto.BillingKeyResponse;
import com.paycore.billing.domain.BillingKey;
import com.paycore.billing.repository.BillingKeyRepository;
import com.paycore.common.exception.ErrorCode;
import com.paycore.common.exception.PaycoreException;
import com.paycore.lock.DistributedLockService;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 빌링키 서비스
 *
 * [보안 주의사항]
 * - pgBillingKey는 복호화된 상태로 결제 API에만 전달.
 * - 복호화된 키는 절대 Response DTO에 담지 않음.
 * - 로그에 pgBillingKey 출력 금지.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingKeyService {

    private final BillingKeyRepository billingKeyRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentMethodRouter paymentMethodRouter;
    private final MerchantService merchantService;
    private final WebhookDispatcher webhookDispatcher;
    private final DistributedLockService distributedLockService;

    @Transactional
    public BillingKeyResponse register(BillingKeyRegisterRequest request) {
        merchantService.getMerchantOrThrow(request.getMerchantId());

        if (request.isDefault()) {
            clearDefaultBillingKey(request.getMerchantId(), request.getUserId());
        }

        BillingKey billingKey = BillingKey.builder()
                .merchantId(request.getMerchantId())
                .userId(request.getUserId())
                .pgBillingKey(request.getPgBillingKey())
                .maskedCardNo(request.getMaskedCardNo())
                .cardCompany(request.getCardCompany())
                .isDefault(request.isDefault())
                .build();

        billingKeyRepository.save(billingKey);
        log.info("[BillingKeyService] 빌링키 등록 - merchantId: {}, userId: {}, masked: {}",
                request.getMerchantId(), request.getUserId(), request.getMaskedCardNo());
        return BillingKeyResponse.of(billingKey);
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
     * [보안] getDecryptedPgBillingKey()로 복호화 → 결제 API에만 전달 → 즉시 GC
     * [중복 결제 방지] 동일 merchantOrderId에 이미 Payment가 있으면 차단.
     * [레이스 컨디션] delete와 charge 동시 호출 시 삭제된 빌링키로 결제되는 케이스 방어 → 분산 락.
     * [Webhook] 결제 완료 후 가맹점에게 즉시 비동기 발송.
     */
    public BillingKeyChargeResponse charge(BillingKeyChargeRequest request) {
        return distributedLockService.executeWithBillingKeyLock(
                request.getBillingKeyId(), () -> doCharge(request));
    }

    @Transactional
    public BillingKeyChargeResponse doCharge(BillingKeyChargeRequest request) {
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

        log.info("[BillingKeyService] 빌링키 결제 시작 - merchantId: {}, merchantOrderId: {}, amount: {}",
                request.getMerchantId(), request.getMerchantOrderId(), request.getAmount());

        BillingResult result = paymentMethodRouter.route(PaymentMethod.CARD).chargeBilling(command);

        if (!"paid".equals(result.getStatus())) {
            log.warn("[BillingKeyService] 빌링키 결제 상태 이상 - status: {}", result.getStatus());
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

        log.info("[BillingKeyService] 빌링키 결제 완료 - merchantOrderId: {}, txId: {}",
                request.getMerchantOrderId(), result.getPaymentKey());

        // 결제 완료 즉시 가맹점 Webhook 발송 (비동기 @Async)
        dispatchBillingWebhook(merchant, payment);

        return BillingKeyChargeResponse.of(payment);
    }

    public void delete(Long billingKeyId, Long userId) {
        distributedLockService.executeWithBillingKeyLock(billingKeyId, () -> {
            doDelete(billingKeyId, userId);
            return null;
        });
    }

    @Transactional
    public void doDelete(Long billingKeyId, Long userId) {
        BillingKey billingKey = billingKeyRepository
                .findByIdAndUserIdAndDeletedFalse(billingKeyId, userId)
                .orElseThrow(() -> new PaycoreException(ErrorCode.BILLING_KEY_NOT_FOUND));
        billingKey.softDelete();
        log.info("[BillingKeyService] 빌링키 삭제 - id: {}, userId: {}", billingKeyId, userId);
    }

    private void clearDefaultBillingKey(String merchantId, Long userId) {
        billingKeyRepository
                .findByMerchantIdAndUserIdAndIsDefaultTrueAndDeletedFalse(merchantId, userId)
                .ifPresent(existing -> existing.setDefault(false));
    }

    private void dispatchBillingWebhook(Merchant merchant, Payment payment) {
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
            log.error("[BillingKeyService] 빌링키 결제 Webhook 발송 실패 - merchantOrderId: {} (수동 확인 필요)",
                    payment.getMerchantOrderId(), e);
        }
    }
}
