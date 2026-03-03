package com.paycore.payment.service;

import com.paycore.common.exception.ErrorCode;
import com.paycore.common.exception.PaycoreException;
import com.paycore.common.exception.PaymentAmountMismatchException;
import com.paycore.order.domain.Order;
import com.paycore.order.repository.OrderRepository;
import com.paycore.payment.client.PortOneClient;
import com.paycore.payment.client.dto.PortOneCancelRequest;
import com.paycore.payment.client.dto.PortOnePaymentResponse;
import com.paycore.payment.controller.dto.PaymentCancelRequest;
import com.paycore.payment.controller.dto.PaymentResponse;
import com.paycore.payment.controller.dto.PaymentVerifyRequest;
import com.paycore.payment.domain.Payment;
import com.paycore.payment.domain.PaymentLog;
import com.paycore.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 결제 핵심 서비스
 *
 * [면접 포인트]
 * 1. noRollbackFor: 금액 불일치 예외 발생 시 order.CANCELLED 상태를 커밋
 * 2. processAfterPayment: verifyAndSavePayment와 트랜잭션 분리
 *    → 결제 확정 후 재고/포인트 처리, 실패 시 PaymentSagaService.cancelBySaga 호출
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PortOneClient portOneClient;
    private final PaymentLogService paymentLogService;
    private final PaymentSagaService paymentSagaService;
    private final InventoryService inventoryService;
    private final PointService pointService;

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

        // 이미 처리된 결제인지 확인 (멱등성)
        if (paymentRepository.existsByImpUid(request.getImpUid())) {
            log.warn("[PaymentService] 이미 처리된 결제 - impUid: {}", request.getImpUid());
            throw new PaycoreException(ErrorCode.PAYMENT_ALREADY_PROCESSED);
        }

        if (order.isPaid()) {
            throw new PaycoreException(ErrorCode.ORDER_ALREADY_PAID);
        }

        // PG API 단건 조회 - 클라이언트 데이터를 신뢰하지 않고 직접 조회
        PortOnePaymentResponse pgPayment = portOneClient.getPaymentByImpUid(request.getImpUid());

        try {
            // [핵심] 금액 위변조 검증
            validatePaymentAmount(order, pgPayment);

            order.markAsPaid();

            Payment payment = Payment.builder()
                    .orderId(order.getId())
                    .impUid(request.getImpUid())
                    .merchantUid(orderNo)
                    .payMethod(pgPayment.getResponse().getPayMethod())
                    .paidAmount(pgPayment.getAmount())
                    .build();
            paymentRepository.save(payment);

            paymentLogService.saveLog(orderNo, PaymentLog.LogType.PAYMENT_VERIFY,
                    request, pgPayment, true, null);

            log.info("[PaymentService] 결제 검증 완료 - orderNo: {}, amount: {}",
                    orderNo, pgPayment.getAmount());

            return PaymentResponse.of(payment, order);

        } catch (PaymentAmountMismatchException e) {
            log.error("[PaymentService] 금액 불일치! orderNo: {}, 주문금액: {}, 결제금액: {}",
                    orderNo, order.getTotalAmount(), pgPayment.getAmount());

            // noRollbackFor 덕분에 CANCELLED 상태가 DB에 커밋됨
            order.markAsCancelled();

            paymentLogService.saveLog(orderNo, PaymentLog.LogType.PAYMENT_VERIFY,
                    request, pgPayment, false, e.getMessage());

            // 즉시 PG 취소
            cancelOnMismatch(request.getImpUid(), orderNo);
            throw e;
        }
    }

    /**
     * 결제 후처리 (Saga 패턴)
     * verifyAndSavePayment 트랜잭션 커밋 후 컨트롤러에서 별도 호출
     *
     * [핵심] 재고 차감 또는 포인트 적립 실패 시
     * PaymentSagaService.cancelBySaga (REQUIRES_NEW) 로 PG + DB 취소
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
            // REQUIRES_NEW 트랜잭션으로 PG + DB 취소 커밋
            paymentSagaService.cancelBySaga(orderNo);
            throw new PaycoreException(ErrorCode.INTERNAL_SERVER_ERROR,
                    "결제 후처리 실패로 결제가 자동 취소되었습니다.");
        }
    }

    /**
     * [면접 포인트] 금액 위변조 검증
     */
    private void validatePaymentAmount(Order order, PortOnePaymentResponse pgPayment) {
        if (!pgPayment.isPaid()) {
            throw new PaycoreException(ErrorCode.PAYMENT_VERIFICATION_FAILED,
                    "PG사 결제 상태가 paid가 아닙니다.");
        }

        if (!pgPayment.getAmount().equals(order.getTotalAmount())) {
            throw new PaymentAmountMismatchException(order.getTotalAmount(), pgPayment.getAmount());
        }
    }

    /**
     * Webhook 처리
     * [핵심] Webhook은 신뢰하지 않음 - PG 단건 조회로 재검증
     */
    @Transactional
    public void processWebhook(String impUid, String merchantUid) {
        log.info("[PaymentService] Webhook 수신 - merchantUid: {}, impUid: {}", merchantUid, impUid);

        Order order = orderRepository.findByOrderNo(merchantUid)
                .orElseThrow(() -> new PaycoreException(ErrorCode.ORDER_NOT_FOUND));

        // 이미 처리된 경우 스킵 (멱등성)
        if (order.isPaid()) {
            log.info("[PaymentService] Webhook 스킵 - 이미 결제 완료 - orderNo: {}", merchantUid);
            return;
        }

        PortOnePaymentResponse pgPayment = portOneClient.getPaymentByImpUid(impUid);
        paymentLogService.saveLog(merchantUid, PaymentLog.LogType.WEBHOOK,
                Map.of("imp_uid", impUid, "merchant_uid", merchantUid), pgPayment,
                pgPayment.isPaid(), null);

        if (pgPayment.isPaid() && !paymentRepository.existsByImpUid(impUid)) {
            validatePaymentAmount(order, pgPayment);
            order.markAsPaid();
            paymentRepository.save(Payment.builder()
                    .orderId(order.getId())
                    .impUid(impUid)
                    .merchantUid(merchantUid)
                    .payMethod(pgPayment.getResponse().getPayMethod())
                    .paidAmount(pgPayment.getAmount())
                    .build());
            log.info("[PaymentService] Webhook으로 결제 완료 처리 - orderNo: {}", merchantUid);
        }
    }

    /**
     * 결제 취소
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

        PortOneCancelRequest cancelRequest = PortOneCancelRequest.builder()
                .impUid(payment.getImpUid())
                .merchantUid(request.getMerchantUid())
                .amount(request.getAmount())
                .reason(request.getReason())
                .build();

        portOneClient.cancelPayment(cancelRequest);

        payment.cancel(request.getAmount() != null ? request.getAmount() : payment.getPaidAmount());
        order.markAsCancelled();

        paymentLogService.saveLog(request.getMerchantUid(), PaymentLog.LogType.PAYMENT_CANCEL,
                cancelRequest, null, true, null);

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

    private void cancelOnMismatch(String impUid, String merchantUid) {
        try {
            portOneClient.cancelPayment(PortOneCancelRequest.builder()
                    .impUid(impUid)
                    .merchantUid(merchantUid)
                    .reason("금액 불일치 자동 취소")
                    .build());
        } catch (Exception e) {
            log.error("[PaymentService] 금액 불일치 자동 취소 실패 (수동 처리 필요) - impUid: {}", impUid, e);
        }
    }
}
