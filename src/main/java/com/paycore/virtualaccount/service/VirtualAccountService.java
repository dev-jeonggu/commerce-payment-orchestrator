package com.paycore.virtualaccount.service;

import com.paycore.common.exception.ErrorCode;
import com.paycore.common.exception.PaycoreException;
import com.paycore.merchant.domain.Merchant;
import com.paycore.merchant.service.MerchantService;
import com.paycore.payment.domain.Payment;
import com.paycore.payment.method.PaymentMethod;
import com.paycore.payment.method.PaymentMethodRouter;
import com.paycore.payment.method.cmd.VirtualAccountCommand;
import com.paycore.payment.method.cmd.VirtualAccountResult;
import com.paycore.payment.repository.PaymentRepository;
import com.paycore.virtualaccount.controller.dto.VirtualAccountIssueRequest;
import com.paycore.virtualaccount.controller.dto.VirtualAccountResponse;
import com.paycore.virtualaccount.domain.VirtualAccount;
import com.paycore.virtualaccount.domain.VirtualAccountStatus;
import com.paycore.virtualaccount.repository.VirtualAccountRepository;
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
public class VirtualAccountService {

    private static final int DEFAULT_DUE_DAYS = 3;

    private final VirtualAccountRepository virtualAccountRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentMethodRouter paymentMethodRouter;
    private final MerchantService merchantService;
    private final WebhookDispatcher webhookDispatcher;

    /**
     * 가상계좌 발급
     */
    @Transactional
    public VirtualAccountResponse issue(VirtualAccountIssueRequest request) {
        merchantService.getMerchantOrThrow(request.getMerchantId());

        virtualAccountRepository.findByMerchantOrderId(request.getMerchantOrderId()).ifPresent(existing -> {
            if (existing.isIssued()) {
                log.info("[VirtualAccountService] 이미 발급된 가상계좌 재반환 - merchantOrderId: {}",
                        request.getMerchantOrderId());
                throw new AlreadyIssuedVirtualAccountException(existing);
            }
            if (existing.isDeposited()) {
                throw new PaycoreException(ErrorCode.PAYMENT_ALREADY_PROCESSED, "이미 입금 확인된 주문입니다.");
            }
            throw new PaycoreException(ErrorCode.VIRTUAL_ACCOUNT_EXPIRED,
                    "입금 기한이 만료된 가상계좌입니다. 새 주문을 생성 후 다시 시도해주세요.");
        });

        LocalDateTime dueDate = request.getDueDate() != null
                ? request.getDueDate()
                : LocalDateTime.now().plusDays(DEFAULT_DUE_DAYS);

        VirtualAccountCommand command = VirtualAccountCommand.builder()
                .orderId(request.getMerchantOrderId())
                .amount(request.getAmount())
                .orderName(request.getOrderName())
                .bankCode(request.getBankCode())
                .holderName(request.getHolderName())
                .dueDate(dueDate)
                .build();

        VirtualAccountResult result = paymentMethodRouter.route(PaymentMethod.VIRTUAL_ACCOUNT)
                .issueVirtualAccount(command);

        VirtualAccount virtualAccount = VirtualAccount.builder()
                .merchantOrderId(request.getMerchantOrderId())
                .txId(result.getPaymentKey())
                .merchantId(request.getMerchantId())
                .bankCode(result.getBankCode())
                .bankName(result.getBankName())
                .accountNumber(result.getAccountNumber())
                .holderName(result.getHolderName())
                .amount(request.getAmount())
                .dueDate(result.getDueDate() != null ? result.getDueDate() : dueDate)
                .build();

        virtualAccountRepository.save(virtualAccount);

        log.info("[VirtualAccountService] 가상계좌 발급 완료 - merchantOrderId: {}, bank: {}, account: {}",
                request.getMerchantOrderId(), result.getBankName(), result.getAccountNumber());

        return VirtualAccountResponse.of(virtualAccount);
    }

    @Transactional(readOnly = true)
    public VirtualAccountResponse getByMerchantOrderId(String merchantOrderId) {
        VirtualAccount va = virtualAccountRepository.findByMerchantOrderId(merchantOrderId)
                .orElseThrow(() -> new PaycoreException(ErrorCode.VIRTUAL_ACCOUNT_NOT_FOUND));
        return VirtualAccountResponse.of(va);
    }

    /**
     * 입금 확인 처리
     *
     * 호출 경로:
     *   1. PaymentService.processWebhook() — 은행 Webhook 수신 시
     *   2. PaymentRecoveryService.recoverVirtualAccountWithTx() — 스케줄러 복구 시
     *
     * [Webhook 발송 책임]
     * 어떤 경로로 호출되든 가맹점에게 반드시 통보해야 하므로 processDeposit 내부에서 발송.
     * 멱등성: 이미 DEPOSITED면 skip — Webhook도 재발송하지 않음.
     */
    @Transactional
    public void processDeposit(String txId) {
        VirtualAccount va = virtualAccountRepository.findByTxId(txId)
                .orElseThrow(() -> new PaycoreException(ErrorCode.VIRTUAL_ACCOUNT_NOT_FOUND));

        if (va.isDeposited()) {
            log.info("[VirtualAccountService] 이미 입금 처리된 가상계좌 - txId: {}", txId);
            return;
        }

        if (va.getStatus() == VirtualAccountStatus.EXPIRED) {
            log.warn("[VirtualAccountService] 만료된 가상계좌에 입금 Webhook 수신 - txId: {}", txId);
            throw new PaycoreException(ErrorCode.VIRTUAL_ACCOUNT_EXPIRED);
        }

        va.markAsDeposited();

        Payment payment = Payment.builder()
                .merchantId(va.getMerchantId())
                .txId(txId)
                .merchantOrderId(va.getMerchantOrderId())
                .paymentMethod(PaymentMethod.VIRTUAL_ACCOUNT)
                .paidAmount(va.getAmount())
                .build();
        paymentRepository.save(payment);

        log.info("[VirtualAccountService] 가상계좌 입금 확인 완료 - merchantOrderId: {}, amount: {}",
                va.getMerchantOrderId(), va.getAmount());

        // 입금 확인 즉시 가맹점 Webhook 발송 (비동기 @Async)
        dispatchDepositWebhook(va.getMerchantId(), payment);
    }

    private void dispatchDepositWebhook(String merchantId, Payment payment) {
        try {
            Merchant merchant = merchantService.getMerchantOrThrow(merchantId);
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
            log.error("[VirtualAccountService] 입금 확인 Webhook 발송 실패 - merchantOrderId: {} (수동 확인 필요)",
                    payment.getMerchantOrderId(), e);
        }
    }

    public static class AlreadyIssuedVirtualAccountException extends RuntimeException {
        public final VirtualAccount virtualAccount;
        public AlreadyIssuedVirtualAccountException(VirtualAccount va) {
            super("already issued");
            this.virtualAccount = va;
        }
    }
}
