package com.paycore.payment.service;

import com.paycore.common.exception.ErrorCode;
import com.paycore.common.exception.PaycoreException;
import com.paycore.merchant.domain.Merchant;
import com.paycore.merchant.domain.MerchantStatus;
import com.paycore.merchant.service.MerchantService;
import com.paycore.payment.controller.dto.PaymentCancelRequest;
import com.paycore.payment.controller.dto.PaymentRequest;
import com.paycore.payment.controller.dto.PaymentResponse;
import com.paycore.payment.domain.Payment;
import com.paycore.payment.domain.PaymentLog;
import com.paycore.payment.domain.PaymentStatus;
import com.paycore.payment.method.PaymentMethod;
import com.paycore.payment.method.PaymentMethodRouter;
import com.paycore.payment.method.cmd.*;
import com.paycore.payment.repository.PaymentRepository;
import com.paycore.virtualaccount.repository.VirtualAccountRepository;
import com.paycore.virtualaccount.service.VirtualAccountService;
import com.paycore.webhook.WebhookDispatcher;
import com.paycore.webhook.dto.WebhookPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentMethodRouter paymentMethodRouter;
    private final PaymentLogService paymentLogService;
    private final MerchantService merchantService;
    private final WebhookDispatcher webhookDispatcher;
    private final VirtualAccountRepository virtualAccountRepository;
    private final VirtualAccountService virtualAccountService;

    /**
     * 가맹점 결제 요청 처리 (카드, 휴대폰, 계좌이체)
     *
     * [중요] 가상계좌는 이 엔드포인트로 처리하지 않음.
     * 가상계좌는 /api/v1/virtual-accounts로 발급 후 입금 대기 → 은행 Webhook으로 확인.
     * 가상계좌를 여기서 처리하면 입금 전에 Payment가 PAID로 저장되는 치명적 버그 발생.
     */
    @Transactional
    public PaymentResponse requestPayment(PaymentRequest request) {
        if (request.getPaymentMethod() == PaymentMethod.VIRTUAL_ACCOUNT) {
            throw new PaycoreException(ErrorCode.PAYMENT_METHOD_NOT_SUPPORTED,
                    "가상계좌는 POST /api/v1/virtual-accounts 를 사용하세요.");
        }

        Merchant merchant = merchantService.getMerchantOrThrow(request.getMerchantId());
        if (merchant.getStatus() == MerchantStatus.SUSPENDED) {
            throw new PaycoreException(ErrorCode.MERCHANT_SUSPENDED);
        }

        if (paymentRepository.existsByMerchantOrderId(request.getMerchantOrderId())) {
            throw new PaycoreException(ErrorCode.PAYMENT_ALREADY_PROCESSED);
        }

        log.info("[PaymentService] 결제 요청 - merchantId: {}, merchantOrderId: {}, amount: {}",
                request.getMerchantId(), request.getMerchantOrderId(), request.getAmount());

        PaymentCommand command = PaymentCommand.builder()
                .merchantId(request.getMerchantId())
                .merchantOrderId(request.getMerchantOrderId())
                .amount(request.getAmount())
                .paymentMethod(request.getPaymentMethod())
                .orderName(request.getOrderName())
                .build();

        PaymentDetail detail = paymentMethodRouter.route(request.getPaymentMethod()).processPayment(command);

        Payment payment = Payment.builder()
                .merchantId(request.getMerchantId())
                .txId(detail.getPaymentKey())
                .merchantOrderId(request.getMerchantOrderId())
                .paymentMethod(request.getPaymentMethod())
                .paidAmount(request.getAmount())
                .build();
        paymentRepository.save(payment);

        paymentLogService.saveLog(request.getMerchantOrderId(), PaymentLog.LogType.PAYMENT_VERIFY,
                request, detail, true, null);

        if (detail.isPaid()) {
            dispatchWebhook(merchant, payment, "paid");
        }

        log.info("[PaymentService] 결제 완료 - txId: {}", detail.getPaymentKey());
        return PaymentResponse.of(payment);
    }

    /**
     * 결제 취소
     */
    @Transactional
    public PaymentResponse cancelPayment(String merchantId, PaymentCancelRequest request) {
        merchantService.getMerchantOrThrow(merchantId);

        Payment payment = paymentRepository.findByMerchantOrderId(request.getMerchantOrderId())
                .orElseThrow(() -> new PaycoreException(ErrorCode.PAYMENT_NOT_FOUND));

        if (!payment.getMerchantId().equals(merchantId)) {
            throw new PaycoreException(ErrorCode.PAYMENT_NOT_FOUND);
        }

        long cancelAmount = request.getAmount() != null ? request.getAmount() : payment.getPaidAmount();
        long remainingCancellable = payment.getPaidAmount() - payment.getCancelledAmount();
        if (cancelAmount > remainingCancellable) {
            throw new PaycoreException(ErrorCode.CANCEL_AMOUNT_EXCEEDED,
                    String.format("취소 요청 금액(%d)이 취소 가능 금액(%d)을 초과합니다.", cancelAmount, remainingCancellable));
        }

        CancelCommand cancelCommand = CancelCommand.builder()
                .paymentKey(payment.getTxId())
                .orderId(request.getMerchantOrderId())
                .amount(request.getAmount())
                .reason(request.getReason())
                .build();
        paymentMethodRouter.route(payment.getPaymentMethod()).cancel(cancelCommand);

        payment.cancel(cancelAmount);

        paymentLogService.saveLog(request.getMerchantOrderId(), PaymentLog.LogType.PAYMENT_CANCEL,
                cancelCommand, null, true, null);

        log.info("[PaymentService] 결제 취소 완료 - merchantOrderId: {}", request.getMerchantOrderId());
        return PaymentResponse.of(payment);
    }

    /**
     * 결제 조회
     */
    @Transactional(readOnly = true)
    public PaymentResponse getPayment(String merchantId, String merchantOrderId) {
        Payment payment = paymentRepository.findByMerchantOrderId(merchantOrderId)
                .orElseThrow(() -> new PaycoreException(ErrorCode.PAYMENT_NOT_FOUND));

        if (!payment.getMerchantId().equals(merchantId)) {
            throw new PaycoreException(ErrorCode.PAYMENT_NOT_FOUND);
        }

        return PaymentResponse.of(payment);
    }

    /**
     * 은행/통신사로부터 입금 확인 Webhook 수신 (내부용)
     *
     * [가상계좌 vs 일반 결제 분기]
     * txId로 VirtualAccount가 존재하면 → 가상계좌 입금 확인 흐름
     *   1. VirtualAccountService.processDeposit() → VA.DEPOSITED + Payment 생성
     *   2. 생성된 Payment로 가맹점 Webhook 발송
     *
     * VA가 아닌 경우(향후 확장: 계좌이체 등) → 기존 Payment 상태 재확인
     */
    @Transactional
    public void processWebhook(String txId, String merchantOrderId) {
        log.info("[PaymentService] 입금 확인 Webhook 수신 - txId: {}, merchantOrderId: {}", txId, merchantOrderId);

        // 가상계좌 입금 확인 여부 판단
        boolean isVirtualAccount = virtualAccountRepository.findByTxId(txId).isPresent();

        if (isVirtualAccount) {
            // processDeposit 내부에서 Payment 저장 + 가맹점 Webhook 발송까지 처리
            virtualAccountService.processDeposit(txId);
            log.info("[PaymentService] 가상계좌 입금 확인 완료 - merchantOrderId: {}", merchantOrderId);
            return;
        }

        // 일반 결제 재확인 (계좌이체 등 비동기 확인이 필요한 경우)
        Payment payment = paymentRepository.findByMerchantOrderId(merchantOrderId)
                .orElseThrow(() -> new PaycoreException(ErrorCode.PAYMENT_NOT_FOUND));

        if (payment.getStatus() == PaymentStatus.PAID) {
            log.info("[PaymentService] Webhook 스킵 - 이미 처리된 결제 - merchantOrderId: {}", merchantOrderId);
            return;
        }

        paymentLogService.saveLog(merchantOrderId, PaymentLog.LogType.WEBHOOK,
                null, null, true, null);

        Merchant merchant = merchantService.getMerchantOrThrow(payment.getMerchantId());
        dispatchWebhook(merchant, payment, "paid");

        log.info("[PaymentService] 입금 확인 처리 완료 - merchantOrderId: {}", merchantOrderId);
    }

    public void dispatchWebhook(Merchant merchant, Payment payment, String status) {
        WebhookPayload webhookPayload = WebhookPayload.builder()
                .txId(payment.getTxId())
                .merchantOrderId(payment.getMerchantOrderId())
                .status(status)
                .amount(payment.getPaidAmount())
                .paymentMethod(payment.getPaymentMethod().name())
                .paidAt(payment.getCreatedAt() != null ? payment.getCreatedAt() : LocalDateTime.now())
                .build();
        webhookDispatcher.dispatch(merchant, webhookPayload);
    }
}
