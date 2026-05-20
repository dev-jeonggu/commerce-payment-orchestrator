package com.paycore.payment.service;

import com.paycore.common.exception.PaymentAmountMismatchException;
import com.paycore.order.domain.Order;
import com.paycore.order.repository.OrderRepository;
import com.paycore.payment.controller.dto.PaymentVerifyRequest;
import com.paycore.payment.pg.*;
import com.paycore.payment.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("[단위] PaymentService 테스트")
class PaymentServiceTest {

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

    private Order mockOrder;

    @BeforeEach
    void setUp() {
        mockOrder = Order.builder()
                .orderNo("ORD-UNIT-001")
                .userId(1L)
                .itemId(10L)
                .totalAmount(30_000L)
                .build();

        given(pgRouter.route(any(PgProvider.class))).willReturn(gatewayClient);
    }

    @Test
    @DisplayName("[핵심] PG 금액 != 주문 금액 → PaymentAmountMismatchException + PG 취소 호출")
    void verifyPayment_amountMismatch_throwsAndCallsPgCancel() {
        given(orderRepository.findByOrderNo("ORD-UNIT-001")).willReturn(Optional.of(mockOrder));
        given(paymentRepository.existsByImpUid("imp_unit_001")).willReturn(false);
        given(gatewayClient.getPaymentByPaymentKey("imp_unit_001"))
                .willReturn(buildPgDetail("imp_unit_001", 1_000L, "paid"));

        assertThatThrownBy(() -> paymentService.verifyAndSavePayment(
                buildRequest("imp_unit_001", "ORD-UNIT-001")))
                .isInstanceOf(PaymentAmountMismatchException.class)
                .hasMessageContaining("30000")
                .hasMessageContaining("1000");

        verify(gatewayClient, times(1)).cancel(any(PgCancelCommand.class));
    }

    @Test
    @DisplayName("이미 처리된 결제(impUid 중복) → PAYMENT_ALREADY_PROCESSED 예외")
    void verifyPayment_duplicateImpUid_throwsAlreadyProcessed() {
        given(orderRepository.findByOrderNo("ORD-UNIT-001")).willReturn(Optional.of(mockOrder));
        given(paymentRepository.existsByImpUid("imp_unit_dup")).willReturn(true);

        assertThatThrownBy(() -> paymentService.verifyAndSavePayment(
                buildRequest("imp_unit_dup", "ORD-UNIT-001")))
                .hasMessageContaining("이미 처리된 결제");

        verify(gatewayClient, never()).getPaymentByPaymentKey(any());
    }

    @Test
    @DisplayName("Webhook - 이미 PAID 상태 → 스킵 (멱등성)")
    void webhook_alreadyPaid_skip() {
        mockOrder.markAsPaid();
        given(orderRepository.findByOrderNo("ORD-UNIT-001")).willReturn(Optional.of(mockOrder));

        paymentService.processWebhook("imp_wh_001", "ORD-UNIT-001");

        verify(gatewayClient, never()).getPaymentByPaymentKey(any());
    }

    private PaymentVerifyRequest buildRequest(String impUid, String merchantUid) {
        PaymentVerifyRequest r = new PaymentVerifyRequest();
        setField(r, "impUid", impUid);
        setField(r, "merchantUid", merchantUid);
        return r;
    }

    private PgPaymentDetail buildPgDetail(String paymentKey, long amount, String status) {
        return PgPaymentDetail.builder()
                .paymentKey(paymentKey)
                .orderId("ORD-UNIT-001")
                .status(status)
                .payMethod("card")
                .amount(amount)
                .cancelledAmount(0L)
                .build();
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
