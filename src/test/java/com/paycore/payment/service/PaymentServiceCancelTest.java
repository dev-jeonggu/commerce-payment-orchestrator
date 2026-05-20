package com.paycore.payment.service;

import com.paycore.common.exception.ErrorCode;
import com.paycore.common.exception.PaycoreException;
import com.paycore.order.domain.Order;
import com.paycore.order.domain.OrderStatus;
import com.paycore.order.repository.OrderRepository;
import com.paycore.payment.controller.dto.PaymentCancelRequest;
import com.paycore.payment.controller.dto.PaymentResponse;
import com.paycore.payment.domain.Payment;
import com.paycore.payment.domain.PaymentStatus;
import com.paycore.payment.pg.*;
import com.paycore.payment.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
@DisplayName("[단위] PaymentService - 결제 취소")
class PaymentServiceCancelTest {

    @InjectMocks
    private PaymentService paymentService;

    @Mock private OrderRepository orderRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private PgRouter pgRouter;
    @Mock private PaymentGatewayClient gatewayClient;
    @Mock private PaymentLogService paymentLogService;
    @Mock private PaymentSagaService paymentSagaService;
    @Mock private InventoryService inventoryService;
    @Mock private PointService pointService;

    private Order paidOrder;
    private Payment payment;

    @BeforeEach
    void setUp() {
        paidOrder = Order.builder()
                .orderNo("ORD-CANCEL-001")
                .userId(1L)
                .itemId(10L)
                .totalAmount(30_000L)
                .build();
        paidOrder.markAsPaid();

        payment = Payment.builder()
                .orderId(1L)
                .impUid("imp_cancel_001")
                .merchantUid("ORD-CANCEL-001")
                .payMethod("card")
                .paidAmount(30_000L)
                .build();

        given(pgRouter.route(any(PgProvider.class))).willReturn(gatewayClient);
    }

    @Nested
    @DisplayName("전액 취소")
    class FullCancel {

        @Test
        @DisplayName("성공 → 결제 CANCELLED, 주문 CANCELLED")
        void fullCancel_success() {
            given(orderRepository.findByOrderNo("ORD-CANCEL-001")).willReturn(Optional.of(paidOrder));
            given(paymentRepository.findByMerchantUid("ORD-CANCEL-001")).willReturn(Optional.of(payment));

            PaymentResponse response = paymentService.cancelPayment(buildRequest(null));

            assertThat(response.getPaymentStatus()).isEqualTo(PaymentStatus.CANCELLED);
            assertThat(response.getOrderStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(response.getCancelledAmount()).isEqualTo(30_000L);
            verify(gatewayClient, times(1)).cancel(any(PgCancelCommand.class));
        }

        @Test
        @DisplayName("전액 취소 후 재고 복구 + 포인트 회수 호출 확인")
        void fullCancel_restoresInventoryAndDeductsPoints() {
            given(orderRepository.findByOrderNo("ORD-CANCEL-001")).willReturn(Optional.of(paidOrder));
            given(paymentRepository.findByMerchantUid("ORD-CANCEL-001")).willReturn(Optional.of(payment));

            paymentService.cancelPayment(buildRequest(null));

            verify(inventoryService, times(1)).increase(any(), anyInt());
            verify(pointService, times(1)).deduct(any(), any());
        }

        @Test
        @DisplayName("amount=null 이면 전액(paidAmount) 취소")
        void fullCancel_amountNullMeansFullAmount() {
            given(orderRepository.findByOrderNo("ORD-CANCEL-001")).willReturn(Optional.of(paidOrder));
            given(paymentRepository.findByMerchantUid("ORD-CANCEL-001")).willReturn(Optional.of(payment));

            paymentService.cancelPayment(buildRequest(null));

            assertThat(payment.getCancelledAmount()).isEqualTo(30_000L);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        }
    }

    @Nested
    @DisplayName("부분 취소")
    class PartialCancel {

        @Test
        @DisplayName("성공 → 결제 PARTIAL_CANCELLED, 주문은 PAID 유지")
        void partialCancel_orderStaysPaid() {
            given(orderRepository.findByOrderNo("ORD-CANCEL-001")).willReturn(Optional.of(paidOrder));
            given(paymentRepository.findByMerchantUid("ORD-CANCEL-001")).willReturn(Optional.of(payment));

            PaymentResponse response = paymentService.cancelPayment(buildRequest(10_000L));

            assertThat(response.getPaymentStatus()).isEqualTo(PaymentStatus.PARTIAL_CANCELLED);
            assertThat(response.getOrderStatus()).isEqualTo(OrderStatus.PAID);
            assertThat(response.getCancelledAmount()).isEqualTo(10_000L);
        }

        @Test
        @DisplayName("부분 취소 시 재고/포인트 복구 호출 없음 (주문 PAID 유지)")
        void partialCancel_noRestoration() {
            given(orderRepository.findByOrderNo("ORD-CANCEL-001")).willReturn(Optional.of(paidOrder));
            given(paymentRepository.findByMerchantUid("ORD-CANCEL-001")).willReturn(Optional.of(payment));

            paymentService.cancelPayment(buildRequest(10_000L));

            verify(inventoryService, never()).increase(any(), anyInt());
            verify(pointService, never()).deduct(any(), any());
        }
    }

    @Nested
    @DisplayName("실패 케이스")
    class FailureCases {

        @Test
        @DisplayName("주문 없음 → ORDER_NOT_FOUND 예외")
        void cancel_orderNotFound() {
            given(orderRepository.findByOrderNo("ORD-CANCEL-001")).willReturn(Optional.empty());

            assertThatThrownBy(() -> paymentService.cancelPayment(buildRequest(null)))
                    .isInstanceOf(PaycoreException.class)
                    .hasMessageContaining("주문을 찾을 수 없습니다");

            verify(gatewayClient, never()).cancel(any());
        }

        @Test
        @DisplayName("PAID 아닌 주문 취소 시도 → INVALID_ORDER_STATUS 예외")
        void cancel_orderNotPaid() {
            Order pendingOrder = Order.builder()
                    .orderNo("ORD-CANCEL-001")
                    .userId(1L).itemId(10L).totalAmount(30_000L)
                    .build();
            given(orderRepository.findByOrderNo("ORD-CANCEL-001")).willReturn(Optional.of(pendingOrder));

            assertThatThrownBy(() -> paymentService.cancelPayment(buildRequest(null)))
                    .isInstanceOf(PaycoreException.class)
                    .hasMessageContaining("결제 완료 상태의 주문만 취소할 수 있습니다");

            verify(gatewayClient, never()).cancel(any());
        }

        @Test
        @DisplayName("PG 취소 API 실패 → 예외 발생, DB 미변경")
        void cancel_pgApiFails_dbNotChanged() {
            given(orderRepository.findByOrderNo("ORD-CANCEL-001")).willReturn(Optional.of(paidOrder));
            given(paymentRepository.findByMerchantUid("ORD-CANCEL-001")).willReturn(Optional.of(payment));
            given(gatewayClient.cancel(any())).willThrow(
                    new PaycoreException(ErrorCode.PAYMENT_CANCEL_FAILED));

            assertThatThrownBy(() -> paymentService.cancelPayment(buildRequest(null)))
                    .isInstanceOf(PaycoreException.class)
                    .hasMessageContaining("결제 취소에 실패했습니다");

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
            assertThat(paidOrder.getStatus()).isEqualTo(OrderStatus.PAID);
        }
    }

    private PaymentCancelRequest buildRequest(Long amount) {
        PaymentCancelRequest req = new PaymentCancelRequest();
        setField(req, "merchantUid", "ORD-CANCEL-001");
        setField(req, "reason", "테스트 취소");
        setField(req, "amount", amount);
        return req;
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
