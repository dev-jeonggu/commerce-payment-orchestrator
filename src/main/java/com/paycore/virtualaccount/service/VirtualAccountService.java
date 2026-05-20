package com.paycore.virtualaccount.service;

import com.paycore.common.exception.ErrorCode;
import com.paycore.common.exception.PaycoreException;
import com.paycore.order.domain.Order;
import com.paycore.order.repository.OrderRepository;
import com.paycore.payment.domain.Payment;
import com.paycore.payment.domain.PaymentStatus;
import com.paycore.payment.pg.*;
import com.paycore.payment.repository.PaymentRepository;
import com.paycore.payment.service.InventoryService;
import com.paycore.payment.service.PointService;
import com.paycore.virtualaccount.controller.dto.VirtualAccountIssueRequest;
import com.paycore.virtualaccount.controller.dto.VirtualAccountResponse;
import com.paycore.virtualaccount.domain.VirtualAccount;
import com.paycore.virtualaccount.domain.VirtualAccountStatus;
import com.paycore.virtualaccount.repository.VirtualAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 가상계좌 서비스
 *
 * [흐름]
 * 1. 발급: 주문 확인 → PG API 가상계좌 발급 → VirtualAccount 저장 → Order.PENDING_PAYMENT
 * 2. 입금 확인: PG Webhook(vbank_paid) 수신 → VirtualAccount.DEPOSITED → Order.PAID → Payment 생성
 *    → 재고 차감 + 포인트 적립
 * 3. 만료: 스케줄러가 ISSUED + dueDate < now → VirtualAccount.EXPIRED → Order.CANCELLED
 *
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VirtualAccountService {

    private static final int DEFAULT_DUE_DAYS = 3;

    private final VirtualAccountRepository virtualAccountRepository;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PgRouter pgRouter;
    private final InventoryService inventoryService;
    private final PointService pointService;

    /**
     * 가상계좌 발급
     *
     * [중복 발급 방지]
     * - 동일 orderNo로 이미 ISSUED 상태 VA가 있으면 기존 것 반환 (멱등성)
     * - DEPOSITED는 이미 결제 완료이므로 409
     * - EXPIRED는 재발급 허용 (만료 후 다시 시도하는 케이스)
     */
    @Transactional
    public VirtualAccountResponse issue(VirtualAccountIssueRequest request) {
        Order order = orderRepository.findByOrderNo(request.getOrderNo())
                .orElseThrow(() -> new PaycoreException(ErrorCode.ORDER_NOT_FOUND));

        // 이미 결제 완료된 주문이면 차단
        if (order.isPaid()) {
            throw new PaycoreException(ErrorCode.ORDER_ALREADY_PAID);
        }

        // 동일 주문 VA 중복 발급 처리
        virtualAccountRepository.findByOrderNo(request.getOrderNo()).ifPresent(existing -> {
            if (existing.isIssued()) {
                // 멱등성: 이미 발급된 활성 VA가 있으면 기존 것 반환
                log.info("[VirtualAccountService] 이미 발급된 가상계좌 재반환 - orderNo: {}", request.getOrderNo());
                throw new AlreadyIssuedVirtualAccountException(existing);
            }
            if (existing.isDeposited()) {
                throw new PaycoreException(ErrorCode.ORDER_ALREADY_PAID, "이미 입금 확인된 주문입니다.");
            }
            // EXPIRED: 해당 주문은 이미 CANCELLED 처리됨.
            // orderNo UNIQUE 제약상 신규 VA 저장 불가 → 새 주문 생성 필요.
            //
            // [설계 결정] 만료된 VA의 재발급을 허용하지 않는 이유:
            //   만료 스케줄러가 Order.markAsCancelled()를 함께 처리하므로
            //   CANCELLED 주문을 PENDING으로 역전환하는 로직이 필요해짐.
            //   상태 역전환은 결제 이력 무결성을 훼손할 위험이 있어 허용하지 않음.
            //   → 클라이언트는 새 주문을 생성 후 VA를 재발급해야 함.
            throw new PaycoreException(ErrorCode.VIRTUAL_ACCOUNT_EXPIRED,
                    "입금 기한이 만료된 가상계좌입니다. 새 주문을 생성 후 다시 시도해주세요.");
        });

        PgProvider pgProvider = request.getPgProvider() != null
                ? request.getPgProvider()
                : order.getPgProvider();

        LocalDateTime dueDate = request.getDueDate() != null
                ? request.getDueDate()
                : LocalDateTime.now().plusDays(DEFAULT_DUE_DAYS);

        // PG API 가상계좌 발급
        PgVirtualAccountCommand command = PgVirtualAccountCommand.builder()
                .orderId(request.getOrderNo())
                .amount(request.getAmount())
                .orderName(request.getOrderName())
                .bankCode(request.getBankCode())
                .holderName(request.getHolderName())
                .dueDate(dueDate)
                .build();

        PgVirtualAccountResult result = pgRouter.route(pgProvider).issueVirtualAccount(command);

        VirtualAccount virtualAccount = VirtualAccount.builder()
                .orderNo(request.getOrderNo())
                .impUid(result.getPaymentKey())
                .pgProvider(pgProvider)
                .bankCode(result.getBankCode())
                .bankName(result.getBankName())
                .accountNumber(result.getAccountNumber())
                .holderName(result.getHolderName())
                .amount(request.getAmount())
                .dueDate(result.getDueDate() != null ? result.getDueDate() : dueDate)
                .build();

        virtualAccountRepository.save(virtualAccount);

        // 주문 상태: PENDING → PENDING_PAYMENT (입금 대기)
        order.markAsPendingPayment();

        log.info("[VirtualAccountService] 가상계좌 발급 완료 - orderNo: {}, bank: {}, account: {}",
                request.getOrderNo(), result.getBankName(), result.getAccountNumber());

        return VirtualAccountResponse.of(virtualAccount);
    }

    /**
     * 가상계좌 조회
     */
    @Transactional(readOnly = true)
    public VirtualAccountResponse getByOrderNo(String orderNo) {
        VirtualAccount va = virtualAccountRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new PaycoreException(ErrorCode.VIRTUAL_ACCOUNT_NOT_FOUND));
        return VirtualAccountResponse.of(va);
    }

    /**
     * 입금 확인 처리 (PG Webhook → PaymentService에서 호출)
     *
     * [트랜잭션 전파]
     * PaymentService.processWebhook이 @Transactional 안에서 이 메서드를 호출함.
     * 동일 TX 참여 → VirtualAccount, Order, Payment 변경이 하나의 TX로 묶임.
     *
     * [멱등성]
     * 이미 DEPOSITED인 경우 중복 Webhook이므로 skip (예외 없이 처리).
     */
    @Transactional
    public void processDeposit(String impUid) {
        VirtualAccount va = virtualAccountRepository.findByImpUid(impUid)
                .orElseThrow(() -> new PaycoreException(ErrorCode.VIRTUAL_ACCOUNT_NOT_FOUND));

        // 멱등성: 이미 입금 처리된 경우 스킵
        if (va.isDeposited()) {
            log.info("[VirtualAccountService] 이미 입금 처리된 가상계좌 - impUid: {}", impUid);
            return;
        }

        if (va.getStatus() == VirtualAccountStatus.EXPIRED) {
            log.warn("[VirtualAccountService] 만료된 가상계좌에 입금 Webhook 수신 - impUid: {}", impUid);
            throw new PaycoreException(ErrorCode.VIRTUAL_ACCOUNT_EXPIRED);
        }

        va.markAsDeposited();

        Order order = orderRepository.findByOrderNo(va.getOrderNo())
                .orElseThrow(() -> new PaycoreException(ErrorCode.ORDER_NOT_FOUND));

        order.markAsPaid();

        // Payment 생성 (입금 확인 시점에 생성)
        Payment payment = Payment.builder()
                .orderId(order.getId())
                .impUid(impUid)
                .merchantUid(va.getOrderNo())
                .payMethod("vbank")
                .paidAmount(va.getAmount())
                .pgProvider(va.getPgProvider())
                .build();
        paymentRepository.save(payment);

        log.info("[VirtualAccountService] 가상계좌 입금 확인 완료 - orderNo: {}, amount: {}",
                va.getOrderNo(), va.getAmount());

        // 재고 차감 + 포인트 적립 (best-effort, 실패 시 로그만)
        processAfterDeposit(order);
    }

    /**
     * 입금 후처리: 재고 차감 + 포인트 적립
     *
     */
    private void processAfterDeposit(Order order) {
        try {
            inventoryService.decrease(order.getItemId(), 1);
        } catch (Exception e) {
            log.error("[VirtualAccountService] 입금 후 재고 차감 실패 (수동 처리 필요) - orderNo: {}",
                    order.getOrderNo(), e);
        }
        try {
            pointService.earn(order.getUserId(), order.getTotalAmount());
        } catch (Exception e) {
            log.error("[VirtualAccountService] 입금 후 포인트 적립 실패 (수동 처리 필요) - orderNo: {}",
                    order.getOrderNo(), e);
        }
    }

    /**
     * 멱등성 처리용 내부 예외 (AlreadyIssued → 기존 VA 그대로 반환)
     */
    public static class AlreadyIssuedVirtualAccountException extends RuntimeException {
        public final VirtualAccount virtualAccount;
        public AlreadyIssuedVirtualAccountException(VirtualAccount va) {
            super("already issued");
            this.virtualAccount = va;
        }
    }
}
