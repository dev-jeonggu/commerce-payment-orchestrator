package com.paycore.payment.integration;

import com.paycore.order.domain.Order;
import com.paycore.order.domain.OrderStatus;
import com.paycore.payment.client.dto.PortOnePaymentResponse;
import com.paycore.payment.controller.dto.PaymentVerifyRequest;
import com.paycore.payment.domain.Payment;
import com.paycore.payment.service.PaymentService;
import com.paycore.scheduler.PaymentRecoveryScheduler;
import com.paycore.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * [테스트 2-1] Webhook 유실 시 스케줄러 복구 테스트
 *
 * 시나리오: PG는 결제 완료, Webhook은 유실 → 스케줄러 실행
 * 기대 결과: order.status = PAID
 *
 * [테스트 2-2] 재고 차감 실패 시 Saga 보상 트랜잭션 테스트
 *
 * 시나리오: PG 결제 성공 → inventoryService.decrease() 예외 발생
 * 기대 결과: PG 취소 호출 + order.status = CANCELLED
 */
@DisplayName("2. 장애 복구 / Saga 테스트")
class PaymentRecoveryTest extends AbstractIntegrationTest {

    @Autowired
    private PaymentRecoveryScheduler scheduler;

    @Autowired
    private PaymentService paymentService;

    @Test
    @DisplayName("2-1. Webhook 유실 → 스케줄러가 30분 후 PENDING 주문 자동 PAID 처리")
    void webhookLost_schedulerRecoversToPaid() throws Exception {
        // Given: 31분 전에 생성된 PENDING 주문 (DB에 직접 시간 조작)
        Order order = createPendingOrder(10_000L);
        forceCreatedAt(order, LocalDateTime.now().minusMinutes(31));

        // PG는 결제 완료 상태 반환
        PortOnePaymentResponse pgResponse = mock(PortOnePaymentResponse.class);
        PortOnePaymentResponse.PaymentData data = mock(PortOnePaymentResponse.PaymentData.class);
        given(pgResponse.isPaid()).willReturn(true);
        given(pgResponse.getAmount()).willReturn(10_000L);
        given(pgResponse.getResponse()).willReturn(data);
        given(data.getImpUid()).willReturn("imp_recovery_001");
        given(data.getPayMethod()).willReturn("card");
        given(portOneClient.getPaymentByMerchantUid(any())).willReturn(pgResponse);

        // threshold를 0분으로 설정해서 스케줄러 즉시 실행
        ReflectionTestUtils.setField(scheduler, "pendingThresholdMinutes", 0);

        // When: 스케줄러 수동 실행 (Webhook 유실 복구)
        scheduler.recoverPendingOrders();

        // Then
        Order recovered = orderRepository.findByOrderNo(order.getOrderNo()).orElseThrow();
        Payment payment = paymentRepository.findByMerchantUid(order.getOrderNo()).orElseThrow();

        System.out.println("\n========================================");
        System.out.println("  [2-1] Webhook 유실 복구 결과");
        System.out.println("  주문 생성: 31분 전 (PENDING)");
        System.out.println("  Webhook: 유실");
        System.out.println("  스케줄러 실행 후 상태: " + recovered.getStatus());
        System.out.println("  Payment 생성 여부: " + (payment != null));
        System.out.println("========================================\n");

        assertThat(recovered.getStatus())
                .as("스케줄러가 PENDING 주문을 PAID로 복구해야 합니다")
                .isEqualTo(OrderStatus.PAID);
        assertThat(payment.getImpUid()).isEqualTo("imp_recovery_001");
    }

    @Test
    @DisplayName("2-2. Saga 패턴: 재고 차감 실패 → PG 취소 + 주문 CANCELLED")
    void inventoryFail_sagaCompensation() {
        // Given: 정상 결제 완료 (verifyAndSavePayment 성공, PAID 상태 커밋)
        Order order = createPendingOrder(10_000L);
        mockPgPaid("imp_saga_001", 10_000L);

        PaymentVerifyRequest request = buildRequest("imp_saga_001", order.getOrderNo());
        paymentService.verifyAndSavePayment(request);

        // 결제 완료 확인
        Order paidOrder = orderRepository.findByOrderNo(order.getOrderNo()).orElseThrow();
        assertThat(paidOrder.getStatus()).isEqualTo(OrderStatus.PAID);

        // 재고 차감에서 예외 발생 시뮬레이션
        doThrow(new RuntimeException("재고 부족 - 현재 재고: 0개"))
                .when(inventoryService).decrease(any(), anyInt());

        // PG 취소는 성공하도록 설정
        given(portOneClient.cancelPayment(any())).willReturn(mock(PortOnePaymentResponse.class));

        // When: 결제 후처리 (재고 차감 실패)
        try {
            paymentService.processAfterPayment(order.getOrderNo());
        } catch (Exception ignored) {
            // Saga 보상 후 예외 재발생 expected
        }

        // Then: Saga 보상 취소 완료
        Order cancelled = orderRepository.findByOrderNo(order.getOrderNo()).orElseThrow();
        Payment payment = paymentRepository.findByMerchantUid(order.getOrderNo()).orElseThrow();

        System.out.println("\n========================================");
        System.out.println("  [2-2] Saga 보상 트랜잭션 결과");
        System.out.println("  1단계 PAID 확정 → 성공");
        System.out.println("  재고 차감 → 실패 (재고 부족)");
        System.out.println("  Saga 보상 취소 후 주문 상태: " + cancelled.getStatus());
        System.out.println("  Payment 취소 상태: " + payment.getStatus());
        System.out.println("========================================\n");

        assertThat(cancelled.getStatus())
                .as("Saga 보상 후 주문은 CANCELLED 되어야 합니다")
                .isEqualTo(OrderStatus.CANCELLED);

        verify(portOneClient, times(1)).cancelPayment(any());
    }

    private void forceCreatedAt(Order order, LocalDateTime time) throws Exception {
        var field = Order.class.getDeclaredField("createdAt");
        field.setAccessible(true);
        field.set(order, time);
        orderRepository.save(order);
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
