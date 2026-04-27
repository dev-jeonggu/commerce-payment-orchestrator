package com.paycore.payment.service;

import com.paycore.common.exception.PaycoreException;
import com.paycore.order.domain.Order;
import com.paycore.order.domain.OrderStatus;
import com.paycore.order.repository.OrderRepository;
import com.paycore.payment.client.PortOneClient;
import com.paycore.payment.domain.Payment;
import com.paycore.payment.domain.PaymentStatus;
import com.paycore.payment.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("[단위] PaymentSagaService - 보상 트랜잭션")
class PaymentSagaServiceTest {

    @InjectMocks
    private PaymentSagaService paymentSagaService;

    @Mock private OrderRepository orderRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private PortOneClient portOneClient;
    @Mock private PaymentLogService paymentLogService;

    private Order paidOrder;
    private Payment payment;

    @BeforeEach
    void setUp() {
        paidOrder = Order.builder()
                .orderNo("ORD-SAGA-001")
                .userId(1L)
                .itemId(10L)
                .totalAmount(30_000L)
                .build();
        paidOrder.markAsPaid();

        payment = Payment.builder()
                .orderId(1L)
                .impUid("imp_saga_001")
                .merchantUid("ORD-SAGA-001")
                .payMethod("card")
                .paidAmount(30_000L)
                .build();
    }

    @Test
    @DisplayName("[핵심] Saga 보상 취소 성공 → 결제/주문 CANCELLED + PG 취소 호출")
    void cancelBySaga_success() {
        given(orderRepository.findByOrderNo("ORD-SAGA-001")).willReturn(Optional.of(paidOrder));
        given(paymentRepository.findByMerchantUid("ORD-SAGA-001")).willReturn(Optional.of(payment));

        paymentSagaService.cancelBySaga("ORD-SAGA-001");

        // PG 취소 1번 호출
        verify(portOneClient, times(1)).cancelPayment(any());

        // 결제 전액 취소
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(payment.getCancelledAmount()).isEqualTo(30_000L);

        // 주문 CANCELLED
        assertThat(paidOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);

        // 로그 저장
        verify(paymentLogService, times(1)).saveLog(any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("주문 없음 → ORDER_NOT_FOUND 예외, PG 호출 없음")
    void cancelBySaga_orderNotFound() {
        given(orderRepository.findByOrderNo("ORD-SAGA-001")).willReturn(Optional.empty());

        assertThatThrownBy(() -> paymentSagaService.cancelBySaga("ORD-SAGA-001"))
                .isInstanceOf(PaycoreException.class)
                .hasMessageContaining("주문을 찾을 수 없습니다");

        verify(portOneClient, never()).cancelPayment(any());
    }

    @Test
    @DisplayName("결제 없음 → PAYMENT_NOT_FOUND 예외, PG 호출 없음")
    void cancelBySaga_paymentNotFound() {
        given(orderRepository.findByOrderNo("ORD-SAGA-001")).willReturn(Optional.of(paidOrder));
        given(paymentRepository.findByMerchantUid("ORD-SAGA-001")).willReturn(Optional.empty());

        assertThatThrownBy(() -> paymentSagaService.cancelBySaga("ORD-SAGA-001"))
                .isInstanceOf(PaycoreException.class)
                .hasMessageContaining("결제 정보를 찾을 수 없습니다");

        verify(portOneClient, never()).cancelPayment(any());
    }

    @Test
    @DisplayName("PG 취소 API 실패 → 예외 전파 (REQUIRES_NEW 트랜잭션 롤백 유도)")
    void cancelBySaga_pgFails_exceptionPropagated() {
        given(orderRepository.findByOrderNo("ORD-SAGA-001")).willReturn(Optional.of(paidOrder));
        given(paymentRepository.findByMerchantUid("ORD-SAGA-001")).willReturn(Optional.of(payment));
        given(portOneClient.cancelPayment(any()))
                .willThrow(new PaycoreException(
                        com.paycore.common.exception.ErrorCode.PAYMENT_CANCEL_FAILED));

        // Saga cancelBySaga는 예외를 삼키지 않고 상위로 전파해야 함
        assertThatThrownBy(() -> paymentSagaService.cancelBySaga("ORD-SAGA-001"))
                .isInstanceOf(PaycoreException.class);
    }
}
