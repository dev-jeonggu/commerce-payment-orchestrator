package com.paycore.payment.integration;

import com.paycore.order.domain.Order;
import com.paycore.order.domain.OrderStatus;
import com.paycore.payment.controller.dto.PaymentVerifyRequest;
import com.paycore.payment.service.PaymentService;
import com.paycore.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [테스트 1-3] Webhook + Verify 동시 처리 멱등성 테스트
 *
 * 시나리오: PG Webhook과 클라이언트 verify가 동시에 도착
 * 기대 결과:
 *   - Payment 레코드 1건만 생성 (중복 없음)
 *   - order.status = PAID
 */
@DisplayName("1-3. Webhook + Verify 동시 멱등성 테스트")
class PaymentWebhookIdempotencyTest extends AbstractIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Test
    @DisplayName("Webhook과 Verify 동시 요청 → Payment 레코드 1건만 생성")
    void webhookAndVerifyConcurrent_onlyOnePaymentCreated() throws InterruptedException {
        // Given
        Order order = createPendingOrder(10_000L);
        String impUid = "imp_idempotency_001";
        mockPgPaid(impUid, 10_000L);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger skipCount = new AtomicInteger(0);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        // Thread 1: Webhook 처리
        executor.submit(() -> {
            try {
                startLatch.await();
                paymentService.processWebhook(impUid, order.getOrderNo());
                successCount.incrementAndGet();
            } catch (Exception e) {
                skipCount.incrementAndGet();
            } finally {
                doneLatch.countDown();
            }
        });

        // Thread 2: Client Verify 처리
        executor.submit(() -> {
            try {
                startLatch.await();
                paymentService.verifyAndSavePayment(buildRequest(impUid, order.getOrderNo()));
                successCount.incrementAndGet();
            } catch (Exception e) {
                skipCount.incrementAndGet();
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown(); // 동시 시작
        boolean completed = doneLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        // Then
        long paymentCount = paymentRepository.findAll().stream()
                .filter(p -> p.getMerchantUid().equals(order.getOrderNo()))
                .count();
        Order finalOrder = orderRepository.findByOrderNo(order.getOrderNo()).orElseThrow();

        System.out.println("\n========================================");
        System.out.println("  [1-3] Webhook + Verify 동시성 결과");
        System.out.println("  성공 처리 수  : " + successCount.get());
        System.out.println("  스킵 처리 수  : " + skipCount.get());
        System.out.println("  Payment 레코드: " + paymentCount + "건");
        System.out.println("  Order 상태    : " + finalOrder.getStatus());
        System.out.println("========================================\n");

        assertThat(completed).isTrue();
        assertThat(paymentCount)
                .as("Payment 레코드는 중복 없이 1건만 생성되어야 합니다")
                .isEqualTo(1);
        assertThat(finalOrder.getStatus()).isEqualTo(OrderStatus.PAID);
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
