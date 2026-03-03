package com.paycore.payment.integration;

import com.paycore.common.exception.PaymentAmountMismatchException;
import com.paycore.order.domain.Order;
import com.paycore.order.domain.OrderStatus;
import com.paycore.payment.controller.dto.PaymentVerifyRequest;
import com.paycore.payment.service.PaymentService;
import com.paycore.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * [테스트 1-1] 금액 위변조 방지 테스트
 *
 * 시나리오: 주문 10,000원 → PG에서 1,000원 결제 (금액 조작 시도)
 * 기대 결과:
 *   - PaymentAmountMismatchException 발생
 *   - PG 자동 취소 요청 1회
 *   - order.status = CANCELLED
 */
@DisplayName("1-1. 금액 위변조 방지 테스트")
class PaymentAmountFraudTest extends AbstractIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Test
    @DisplayName("PG 결제 금액이 주문 금액과 다르면 예외 발생 + PG 취소 + 주문 CANCELLED")
    void whenPgAmountMismatch_thenThrowAndCancelAndMarkCancelled() {
        // Given: 10,000원 주문 생성
        Order order = createPendingOrder(10_000L);

        // PG에서는 1,000원으로 결제됨 (금액 위변조 시뮬레이션)
        mockPgPaid("imp_fraud_001", 1_000L);

        PaymentVerifyRequest request = buildRequest("imp_fraud_001", order.getOrderNo());

        // When & Then: 금액 불일치 예외 발생
        assertThatThrownBy(() -> paymentService.verifyAndSavePayment(request))
                .isInstanceOf(PaymentAmountMismatchException.class)
                .hasMessageContaining("10000")
                .hasMessageContaining("1000");

        // PG 취소 요청이 1회 발생했는지 확인
        verify(portOneClient, times(1)).cancelPayment(any());

        // noRollbackFor 덕분에 CANCELLED 상태가 DB에 커밋됨
        Order updated = orderRepository.findByOrderNo(order.getOrderNo()).orElseThrow();
        assertThat(updated.getStatus())
                .as("금액 불일치 시 주문은 CANCELLED 처리되어야 합니다")
                .isEqualTo(OrderStatus.CANCELLED);

        // Payment 레코드는 생성되지 않아야 함
        assertThat(paymentRepository.findByMerchantUid(order.getOrderNo())).isEmpty();
    }

    @Test
    @DisplayName("금액 일치 시 정상 결제 완료")
    void whenAmountMatches_thenSuccess() {
        // Given
        Order order = createPendingOrder(10_000L);
        mockPgPaid("imp_ok_001", 10_000L);

        PaymentVerifyRequest request = buildRequest("imp_ok_001", order.getOrderNo());

        // When
        paymentService.verifyAndSavePayment(request);

        // Then
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
