package com.paycore.payment.integration;

import com.paycore.common.exception.PaymentAmountMismatchException;
import com.paycore.order.domain.Order;
import com.paycore.order.domain.OrderStatus;
import com.paycore.payment.controller.dto.PaymentVerifyRequest;
import com.paycore.payment.pg.PgCancelResult;
import com.paycore.payment.pg.PgPaymentDetail;
import com.paycore.payment.service.PaymentService;
import com.paycore.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * [테스트 1-1] 금액 위변조 방지 테스트
 */
@DisplayName("1-1. 금액 위변조 방지 테스트")
class PaymentAmountFraudTest extends AbstractIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Test
    @DisplayName("PG 결제 금액이 주문 금액과 다르면 예외 발생 + PG 취소 + 주문 CANCELLED")
    void whenPgAmountMismatch_thenThrowAndCancelAndMarkCancelled() {
        Order order = createPendingOrder(10_000L);

        // PG에서는 1,000원으로 결제됨 (금액 위변조 시뮬레이션)
        PgPaymentDetail fraudDetail = PgPaymentDetail.builder()
                .paymentKey("imp_fraud_001")
                .orderId(order.getOrderNo())
                .status("paid")
                .payMethod("card")
                .amount(1_000L)
                .cancelledAmount(0L)
                .build();
        given(portOneClient.getPaymentByPaymentKey("imp_fraud_001")).willReturn(fraudDetail);
        given(portOneClient.cancel(any())).willReturn(PgCancelResult.of("imp_fraud_001", 1_000L, 0L));

        PaymentVerifyRequest request = buildRequest("imp_fraud_001", order.getOrderNo());

        assertThatThrownBy(() -> paymentService.verifyAndSavePayment(request))
                .isInstanceOf(PaymentAmountMismatchException.class)
                .hasMessageContaining("10000")
                .hasMessageContaining("1000");

        // cancel() 호출 확인 (기존: cancelPayment → 변경: cancel)
        verify(portOneClient, times(1)).cancel(any());

        Order updated = orderRepository.findByOrderNo(order.getOrderNo()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(paymentRepository.findByMerchantUid(order.getOrderNo())).isEmpty();
    }

    @Test
    @DisplayName("금액 일치 시 정상 결제 완료")
    void whenAmountMatches_thenSuccess() {
        Order order = createPendingOrder(10_000L);
        mockPgPaid("imp_ok_001", 10_000L);

        paymentService.verifyAndSavePayment(buildRequest("imp_ok_001", order.getOrderNo()));

        Order updated = orderRepository.findByOrderNo(order.getOrderNo()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(paymentRepository.findByMerchantUid(order.getOrderNo())).isPresent();
    }

    private PaymentVerifyRequest buildRequest(String impUid, String merchantUid) {
        PaymentVerifyRequest r = new PaymentVerifyRequest();
        setField(r, "impUid", impUid);
        setField(r, "merchantUid", merchantUid);
        return r;
    }

    private void setField(Object obj, String name, Object value) {
        try {
            var f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(obj, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
