package com.paycore.payment.service;

import com.paycore.common.exception.ErrorCode;
import com.paycore.common.exception.PaycoreException;
import com.paycore.order.domain.Order;
import com.paycore.order.repository.OrderRepository;
import com.paycore.payment.client.PortOneClient;
import com.paycore.payment.client.dto.PortOneCancelRequest;
import com.paycore.payment.domain.Payment;
import com.paycore.payment.domain.PaymentLog;
import com.paycore.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 보상 트랜잭션(Saga) 서비스
 *
 * [핵심 설계] Propagation.REQUIRES_NEW
 * - 메인 트랜잭션(verifyAndSavePayment)이 커밋된 후 processAfterPayment에서 예외 발생 시
 * - cancelBySaga는 독립 트랜잭션으로 실행되어 반드시 CANCELLED 상태를 커밋
 * - REQUIRES_NEW가 없으면 메인 트랜잭션 롤백 시 보상 처리도 함께 롤백되어 데이터 불일치 발생
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentSagaService {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PortOneClient portOneClient;
    private final PaymentLogService paymentLogService;

    /**
     * 보상 취소 - 독립 트랜잭션으로 실행
     * processAfterPayment 실패 시 호출 → 결제/주문을 CANCELLED로 롤백
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cancelBySaga(String orderNo) {
        log.info("[Saga] 보상 취소 시작 - orderNo: {}", orderNo);

        Order order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new PaycoreException(ErrorCode.ORDER_NOT_FOUND));

        Payment payment = paymentRepository.findByMerchantUid(orderNo)
                .orElseThrow(() -> new PaycoreException(ErrorCode.PAYMENT_NOT_FOUND));

        try {
            PortOneCancelRequest cancelReq = PortOneCancelRequest.builder()
                    .impUid(payment.getImpUid())
                    .merchantUid(orderNo)
                    .reason("결제 후처리 실패로 인한 자동 취소 (Saga)")
                    .build();

            portOneClient.cancelPayment(cancelReq);

            payment.cancel(payment.getPaidAmount());
            order.markAsCancelled();

            paymentLogService.saveLog(orderNo, PaymentLog.LogType.PAYMENT_CANCEL,
                    cancelReq, null, true, "Saga compensation cancel");

            log.info("[Saga] 보상 취소 완료 - orderNo: {}", orderNo);
        } catch (Exception e) {
            log.error("[Saga] 보상 취소 실패 - orderNo: {} (수동 처리 필요)", orderNo, e);
            throw e;
        }
    }
}
