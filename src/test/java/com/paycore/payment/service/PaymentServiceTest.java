package com.paycore.payment.service;

import com.paycore.common.exception.PaymentAmountMismatchException;
import com.paycore.order.domain.Order;
import com.paycore.order.repository.OrderRepository;
import com.paycore.payment.client.PortOneClient;
import com.paycore.payment.client.dto.PortOnePaymentResponse;
import com.paycore.payment.controller.dto.PaymentVerifyRequest;
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
    @Mock private PortOneClient portOneClient;
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
    }

    @Test
    @DisplayName("[핵심] PG 금액 != 주문 금액 → PaymentAmountMismatchException + PG 취소 호출")
    void verifyPayment_amountMismatch_throwsAndCallsPgCancel() {
        // given
        given(orderRepository.findByOrderNo("ORD-UNIT-001")).willReturn(Optional.of(mockOrder));
        given(paymentRepository.existsByImpUid("imp_unit_001")).willReturn(false);
        // mock 먼저 생성 후 stubbing (given 안에서 given 호출 시 UnfinishedStubbingException 방지)
        PortOnePaymentResponse fraudResponse = mockPgResponse("imp_unit_001", 1_000L, "paid");
        given(portOneClient.getPaymentByImpUid("imp_unit_001")).willReturn(fraudResponse);

        // when & then
        assertThatThrownBy(() -> paymentService.verifyAndSavePayment(
                buildRequest("imp_unit_001", "ORD-UNIT-001")))
                .isInstanceOf(PaymentAmountMismatchException.class)
                .hasMessageContaining("30000")
                .hasMessageContaining("1000");

        // PG 취소 호출 확인
        verify(portOneClient, times(1)).cancelPayment(any());
    }

    @Test
    @DisplayName("이미 처리된 결제(impUid 중복) → PAYMENT_ALREADY_PROCESSED 예외")
    void verifyPayment_duplicateImpUid_throwsAlreadyProcessed() {
        // given
        given(orderRepository.findByOrderNo("ORD-UNIT-001")).willReturn(Optional.of(mockOrder));
        given(paymentRepository.existsByImpUid("imp_unit_dup")).willReturn(true);

        // when & then
        assertThatThrownBy(() -> paymentService.verifyAndSavePayment(
                buildRequest("imp_unit_dup", "ORD-UNIT-001")))
                .hasMessageContaining("이미 처리된 결제");

        // PG 호출 없어야 함
        verify(portOneClient, never()).getPaymentByImpUid(any());
    }

    @Test
    @DisplayName("Webhook - 이미 PAID 상태 → 스킵 (멱등성)")
    void webhook_alreadyPaid_skip() {
        // given: order already paid
        mockOrder.markAsPaid();
        given(orderRepository.findByOrderNo("ORD-UNIT-001")).willReturn(Optional.of(mockOrder));

        // when
        paymentService.processWebhook("imp_wh_001", "ORD-UNIT-001");

        // then: PG 조회 없음
        verify(portOneClient, never()).getPaymentByImpUid(any());
    }

    private PaymentVerifyRequest buildRequest(String impUid, String merchantUid) {
        PaymentVerifyRequest r = new PaymentVerifyRequest();
        setField(r, "impUid", impUid);
        setField(r, "merchantUid", merchantUid);
        return r;
    }

    private PortOnePaymentResponse mockPgResponse(String impUid, Long amount, String status) {
        PortOnePaymentResponse response = mock(PortOnePaymentResponse.class);
        PortOnePaymentResponse.PaymentData data = mock(PortOnePaymentResponse.PaymentData.class);
        given(response.isSuccess()).willReturn(true);
        given(response.isPaid()).willReturn("paid".equals(status));
        given(response.getAmount()).willReturn(amount);
        given(response.getResponse()).willReturn(data);
        given(data.getImpUid()).willReturn(impUid);
        given(data.getPayMethod()).willReturn("card");
        return response;
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
