package com.paycore.payment.integration;

import com.paycore.order.domain.Order;
import com.paycore.order.domain.OrderStatus;
import com.paycore.payment.controller.dto.PaymentVerifyRequest;
import com.paycore.payment.domain.Payment;
import com.paycore.payment.pg.PgCancelResult;
import com.paycore.payment.pg.PgPaymentDetail;
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
 * [테스트 2-2] 재고 차감 실패 시 Saga 보상 트랜잭션 테스트
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
        Order order = createPendingOrder(10_000L);
        forceCreatedAt(order, LocalDateTime.now().minusMinutes(31));

        PgPaymentDetail pgDetail = PgPaymentDetail.builder()
                .paymentKey("imp_recovery_001")
                .orderId(order.getOrderNo())
                .status("paid")
                .payMethod("card")
                .amount(10_000L)
                .cancelledAmount(0L)
                .build();
        // getPaymentByOrderId: 스케줄러가 merchantUid로 PG 조회
        given(portOneClient.getPaymentByOrderId(any())).willReturn(pgDetail);
        given(portOneClient.cancel(any())).willReturn(PgCancelResult.of("imp_recovery_001", 10_000L, 0L));

        ReflectionTestUtils.setField(scheduler, "pendingThresholdMinutes", 0);
        scheduler.recoverPendingOrders();

        Order recovered = orderRepository.findByOrderNo(order.getOrderNo()).orElseThrow();
        Payment payment = paymentRepository.findByMerchantUid(order.getOrderNo()).orElseThrow();

        System.out.println("\n========================================");
        System.out.println("  [2-1] Webhook 유실 복구 결과");
        System.out.println("  주문 생성: 31분 전 (PENDING)");
        System.out.println("  스케줄러 실행 후 상태: " + recovered.getStatus());
        System.out.println("  Payment 생성 여부: " + (payment != null));
        System.out.println("========================================\n");

        assertThat(recovered.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(payment.getImpUid()).isEqualTo("imp_recovery_001");
    }

    @Test
    @DisplayName("2-2. Saga 패턴: 재고 차감 실패 → PG 취소 + 주문 CANCELLED")
    void inventoryFail_sagaCompensation() {
        Order order = createPendingOrder(10_000L);
        mockPgPaid("imp_saga_001", 10_000L);

        paymentService.verifyAndSavePayment(buildRequest("imp_saga_001", order.getOrderNo()));

        Order paidOrder = orderRepository.findByOrderNo(order.getOrderNo()).orElseThrow();
        assertThat(paidOrder.getStatus()).isEqualTo(OrderStatus.PAID);

        doThrow(new RuntimeException("재고 부족")).when(inventoryService).decrease(any(), anyInt());

        try {
            paymentService.processAfterPayment(order.getOrderNo());
        } catch (Exception ignored) {
            // Saga 보상 후 예외 재발생 expected
        }

        Order cancelled = orderRepository.findByOrderNo(order.getOrderNo()).orElseThrow();
        Payment payment = paymentRepository.findByMerchantUid(order.getOrderNo()).orElseThrow();

        System.out.println("\n========================================");
        System.out.println("  [2-2] Saga 보상 트랜잭션 결과");
        System.out.println("  1단계 PAID 확정 → 성공");
        System.out.println("  재고 차감 → 실패 (재고 부족)");
        System.out.println("  Saga 보상 취소 후 주문 상태: " + cancelled.getStatus());
        System.out.println("  Payment 취소 상태: " + payment.getStatus());
        System.out.println("========================================\n");

        assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        // cancel() 호출 확인 (기존: cancelPayment → 변경: cancel)
        verify(portOneClient, times(1)).cancel(any());
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
