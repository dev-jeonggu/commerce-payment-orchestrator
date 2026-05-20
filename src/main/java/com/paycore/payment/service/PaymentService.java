package com.paycore.payment.service;

import com.paycore.common.exception.ErrorCode;
import com.paycore.common.exception.PaycoreException;
import com.paycore.common.exception.PaymentAmountMismatchException;
import com.paycore.order.domain.Order;
import com.paycore.order.repository.OrderRepository;
import com.paycore.payment.controller.dto.PaymentCancelRequest;
import com.paycore.payment.controller.dto.PaymentResponse;
import com.paycore.payment.controller.dto.PaymentVerifyRequest;
import com.paycore.payment.domain.Payment;
import com.paycore.payment.domain.PaymentLog;
import com.paycore.payment.domain.PaymentStatus;
import com.paycore.payment.pg.*;
import com.paycore.payment.repository.PaymentRepository;
import com.paycore.virtualaccount.service.VirtualAccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 결제 핵심 서비스
 *
 * [변경 사항]
 * - PortOneClient 직접 의존 제거 → PgRouter 경유 (다중 PG 지원)
 * - cancelPayment: PG 취소 성공 후 재고 복구 + 포인트 회수 추가 (기존 누락 버그 수정)
 *   단, 재고/포인트 복구 실패는 환불 롤백 불가이므로 DLQ + 알람으로 처리
 * - processWebhook: 결제 완료 처리 후 processAfterPayment 호출 (기존 누락 버그 수정)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PgRouter pgRouter;
    private final PaymentLogService paymentLogService;
    private final PaymentSagaService paymentSagaService;
    private final InventoryService inventoryService;
    private final PointService pointService;
    private final VirtualAccountService virtualAccountService;

    /**
     * 결제 사후 검증 (Post-Verification)
     *
     * noRollbackFor: PaymentAmountMismatchException 발생해도 트랜잭션 커밋
     * → order.markAsCancelled() 가 실제 DB에 반영됨
     */
    @Transactional(noRollbackFor = PaymentAmountMismatchException.class)
    public PaymentResponse verifyAndSavePayment(PaymentVerifyRequest request) {
        String orderNo = request.getMerchantUid();
        log.info("[PaymentService] 결제 검증 시작 - orderNo: {}, impUid: {}",
                orderNo, request.getImpUid());

        Order order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new PaycoreException(ErrorCode.ORDER_NOT_FOUND));

        if (paymentRepository.existsByImpUid(request.getImpUid())) {
            log.warn("[PaymentService] 이미 처리된 결제 - impUid: {}", request.getImpUid());
            throw new PaycoreException(ErrorCode.PAYMENT_ALREADY_PROCESSED);
        }

        if (order.isPaid()) {
            throw new PaycoreException(ErrorCode.ORDER_ALREADY_PAID);
        }

        PgPaymentDetail pgPayment = pgRouter.route(order.getPgProvider())
                .getPaymentByPaymentKey(request.getImpUid());

        try {
            validatePaymentAmount(order, pgPayment);

            order.markAsPaid();
            Payment payment = buildPayment(order, pgPayment);
            paymentRepository.save(payment);

            paymentLogService.saveLog(orderNo, PaymentLog.LogType.PAYMENT_VERIFY,
                    request, pgPayment, true, null);

            log.info("[PaymentService] 결제 검증 완료 - orderNo: {}, amount: {}",
                    orderNo, pgPayment.getAmount());
            return PaymentResponse.of(payment, order);

        } catch (PaymentAmountMismatchException e) {
            log.error("[PaymentService] 금액 불일치! orderNo: {}, 주문금액: {}, 결제금액: {}",
                    orderNo, order.getTotalAmount(), pgPayment.getAmount());

            order.markAsCancelled();
            paymentLogService.saveLog(orderNo, PaymentLog.LogType.PAYMENT_VERIFY,
                    request, pgPayment, false, e.getMessage());
            cancelOnMismatch(request.getImpUid(), orderNo, order.getPgProvider());
            throw e;
        }
    }

    /**
     * 결제 후처리 (Saga 패턴)
     * verifyAndSavePayment 트랜잭션 커밋 후 컨트롤러에서 별도 호출
     */
    @Transactional
    public void processAfterPayment(String orderNo) {
        Order order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new PaycoreException(ErrorCode.ORDER_NOT_FOUND));

        try {
            inventoryService.decrease(order.getItemId(), 1);
            pointService.earn(order.getUserId(), order.getTotalAmount());
            log.info("[PaymentService] 결제 후처리 완료 - orderNo: {}", orderNo);
        } catch (Exception e) {
            log.error("[PaymentService] 결제 후처리 실패 → Saga 보상 시작 - orderNo: {}", orderNo, e);
            paymentSagaService.cancelBySaga(orderNo);
            throw new PaycoreException(ErrorCode.INTERNAL_SERVER_ERROR,
                    "결제 후처리 실패로 결제가 자동 취소되었습니다.");
        }
    }

    /**
     * Webhook 처리
     *
     * [지원 케이스]
     * 1. 일반 카드/계좌이체: PG 조회 → paid 확인 → Order.PAID + Payment 생성
     * 2. 가상계좌 입금 확인: PG 조회 → paid + vbank → VirtualAccountService.processDeposit 위임
     *    - VirtualAccount.DEPOSITED + Order.PAID + Payment 생성 + 재고/포인트 처리까지 포함
     *
     * [Webhook 신뢰 원칙]
     * Webhook 바디의 status는 참고만, 실제 상태는 PG 단건 조회로 재확인.
     *
     * [반환값] boolean: 이번 호출로 새로 결제 완료 처리됐는지 (컨트롤러가 후처리 분기용으로 사용)
     */
    @Transactional
    public boolean processWebhook(String impUid, String merchantUid) {
        log.info("[PaymentService] Webhook 수신 - merchantUid: {}, impUid: {}", merchantUid, impUid);

        Order order = orderRepository.findByOrderNo(merchantUid)
                .orElseThrow(() -> new PaycoreException(ErrorCode.ORDER_NOT_FOUND));

        if (order.isPaid()) {
            log.info("[PaymentService] Webhook 스킵 - 이미 결제 완료 - orderNo: {}", merchantUid);
            return false;
        }

        PgPaymentDetail pgPayment = pgRouter.route(order.getPgProvider())
                .getPaymentByPaymentKey(impUid);

        paymentLogService.saveLog(merchantUid, PaymentLog.LogType.WEBHOOK,
                Map.of("imp_uid", impUid, "merchant_uid", merchantUid), pgPayment,
                pgPayment.isPaid(), null);

        if (!pgPayment.isPaid()) {
            log.info("[PaymentService] Webhook 스킵 - PG 상태 미완료: {} - orderNo: {}",
                    pgPayment.getStatus(), merchantUid);
            return false;
        }

        if (paymentRepository.existsByImpUid(impUid)) {
            log.info("[PaymentService] Webhook 스킵 - 이미 처리된 impUid: {}", impUid);
            return false;
        }

        // 가상계좌 입금 확인 Webhook
        if (pgPayment.isVirtualAccount()) {
            log.info("[PaymentService] 가상계좌 입금 확인 Webhook - orderNo: {}", merchantUid);
            virtualAccountService.processDeposit(impUid);
            // VirtualAccountService가 Order/Payment/재고/포인트 모두 처리하므로 여기서는 종료.
            // [버그 수정] false 반환 → processAfterWebhook이 processAfterPayment를 재호출하지 않음.
            // true를 반환하면 재고/포인트가 이중 차감되는 치명적 버그 발생.
            return false;
        }

        // 일반 결제 Webhook (카드, 계좌이체 등)
        validatePaymentAmount(order, pgPayment);
        order.markAsPaid();
        paymentRepository.save(buildPayment(order, pgPayment));
        log.info("[PaymentService] Webhook으로 결제 완료 처리 - orderNo: {}", merchantUid);
        return true;
    }

    /**
     * Webhook 처리 후 후처리 실행 (컨트롤러에서 호출)
     *
     * processWebhook과 분리된 이유:
     * - Webhook은 @Transactional 내에서 실행됨
     * - processAfterPayment도 @Transactional → 동일 TX 안에서 실행되면 Saga의 REQUIRES_NEW가 무력화
     * - 컨트롤러에서 processWebhook 커밋 후 별도 호출로 트랜잭션 경계 분리
     *
     * [가상계좌] processDeposit 내부에서 재고/포인트를 이미 처리하므로 이 메서드 호출 불필요.
     * isVirtualAccount 여부는 processWebhook이 판단하여 반환값(wasNewlyPaid)으로 표현.
     */
    public void processAfterWebhook(String merchantUid, boolean wasNewlyPaid) {
        if (!wasNewlyPaid) return;
        try {
            processAfterPayment(merchantUid);
        } catch (Exception e) {
            log.warn("[PaymentService] Webhook 후처리 실패 (Saga 보상 완료) - orderNo: {}", merchantUid);
        }
    }

    /**
     * 결제 취소
     *
     * 전액 취소 시 재고 복구 + 포인트 회수 (best-effort).
     * 고객 환불은 이미 완료된 상태이므로 재고 복구 실패 시 환불 롤백 불가.
     * 복구 실패 건은 운영 이슈로 로그에 기록하여 수동 처리.
     */
    @Transactional
    public PaymentResponse cancelPayment(PaymentCancelRequest request) {
        log.info("[PaymentService] 결제 취소 요청 - merchantUid: {}", request.getMerchantUid());

        Order order = orderRepository.findByOrderNo(request.getMerchantUid())
                .orElseThrow(() -> new PaycoreException(ErrorCode.ORDER_NOT_FOUND));

        if (!order.isPaid()) {
            throw new PaycoreException(ErrorCode.INVALID_ORDER_STATUS, "결제 완료 상태의 주문만 취소할 수 있습니다.");
        }

        Payment payment = paymentRepository.findByMerchantUid(request.getMerchantUid())
                .orElseThrow(() -> new PaycoreException(ErrorCode.PAYMENT_NOT_FOUND));

        long cancelAmount = request.getAmount() != null ? request.getAmount() : payment.getPaidAmount();
        long remainingCancellable = payment.getPaidAmount() - payment.getCancelledAmount();
        if (cancelAmount > remainingCancellable) {
            throw new PaycoreException(ErrorCode.CANCEL_AMOUNT_EXCEEDED,
                    String.format("취소 요청 금액(%d)이 취소 가능 금액(%d)을 초과합니다.", cancelAmount, remainingCancellable));
        }

        // PG 취소 먼저 (실패 시 DB 변경 없음)
        PgCancelCommand cancelCommand = PgCancelCommand.builder()
                .paymentKey(payment.getImpUid())
                .orderId(request.getMerchantUid())
                .amount(request.getAmount())
                .reason(request.getReason())
                .build();
        pgRouter.route(order.getPgProvider()).cancel(cancelCommand);

        // PG 취소 성공 후 DB 상태 변경
        payment.cancel(cancelAmount);
        boolean isFullCancel = (payment.getStatus() == PaymentStatus.CANCELLED);
        if (isFullCancel) {
            order.markAsCancelled();
        }

        paymentLogService.saveLog(request.getMerchantUid(), PaymentLog.LogType.PAYMENT_CANCEL,
                cancelCommand, null, true, null);

        // 전액 취소 시 재고 복구 + 포인트 회수 (best-effort)
        if (isFullCancel) {
            restoreAfterCancel(order);
        }

        log.info("[PaymentService] 결제 취소 완료 - merchantUid: {}", request.getMerchantUid());
        return PaymentResponse.of(payment, order);
    }

    /**
     * 결제 조회
     */
    @Transactional(readOnly = true)
    public PaymentResponse getPayment(String orderNo) {
        Order order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new PaycoreException(ErrorCode.ORDER_NOT_FOUND));
        Payment payment = paymentRepository.findByMerchantUid(orderNo)
                .orElseThrow(() -> new PaycoreException(ErrorCode.PAYMENT_NOT_FOUND));
        return PaymentResponse.of(payment, order);
    }

    // ─── 내부 헬퍼 ──────────────────────────────────────────────────

    private void validatePaymentAmount(Order order, PgPaymentDetail pgPayment) {
        if (!pgPayment.isPaid()) {
            throw new PaycoreException(ErrorCode.PAYMENT_VERIFICATION_FAILED,
                    "PG사 결제 상태가 paid가 아닙니다.");
        }
        if (!pgPayment.getAmount().equals(order.getTotalAmount())) {
            throw new PaymentAmountMismatchException(order.getTotalAmount(), pgPayment.getAmount());
        }
    }

    private Payment buildPayment(Order order, PgPaymentDetail pgPayment) {
        return Payment.builder()
                .orderId(order.getId())
                .impUid(pgPayment.getPaymentKey())
                .merchantUid(order.getOrderNo())
                .payMethod(pgPayment.getPayMethod())
                .paidAmount(pgPayment.getAmount())
                .pgProvider(order.getPgProvider())
                .build();
    }

    private void cancelOnMismatch(String impUid, String merchantUid, PgProvider pgProvider) {
        try {
            pgRouter.route(pgProvider).cancel(PgCancelCommand.builder()
                    .paymentKey(impUid)
                    .orderId(merchantUid)
                    .reason("금액 불일치 자동 취소")
                    .build());
        } catch (Exception e) {
            log.error("[PaymentService] 금액 불일치 자동 취소 실패 (수동 처리 필요) - impUid: {}", impUid, e);
        }
    }

    /**
     * 취소 후 재고/포인트 복구 (best-effort)
     *
     * [중요] 이 시점에 이미 고객 환불은 완료됨.
     * 복구 실패는 환불 취소 불가 → DLQ + 알람으로 수동 처리.
     * Step 4(DLQ)에서 deadLetterService 주입 후 고도화 예정.
     */
    private void restoreAfterCancel(Order order) {
        try {
            inventoryService.increase(order.getItemId(), 1);
        } catch (Exception e) {
            log.error("[PaymentService] 취소 후 재고 복구 실패 - orderNo: {} (수동 처리 필요)",
                    order.getOrderNo(), e);
        }
        try {
            pointService.deduct(order.getUserId(), order.getTotalAmount());
        } catch (Exception e) {
            log.error("[PaymentService] 취소 후 포인트 회수 실패 - orderNo: {} (수동 처리 필요)",
                    order.getOrderNo(), e);
        }
    }
}
