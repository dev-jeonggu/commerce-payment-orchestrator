package com.paycore.payment.integration;

import com.paycore.common.exception.PaycoreException;
import com.paycore.lock.DistributedLockService;
import com.paycore.order.domain.Order;
import com.paycore.order.domain.OrderStatus;
import com.paycore.payment.controller.dto.PaymentVerifyRequest;
import com.paycore.payment.service.PaymentService;
import com.paycore.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [테스트 1-2] 동시 중복 결제 방지 테스트
 *
 * 시나리오: 동일 orderNo로 10개 동시 요청
 * 기대 결과:
 *   - 성공: 1건
 *   - 스킵(중복/락 실패): 9건
 *   - DB Payment 레코드: 1건만 생성
 */
@DisplayName("1-2. Redis 분산락 동시성 테스트")
class PaymentConcurrentTest extends AbstractIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private DistributedLockService distributedLockService;

    @Test
    @DisplayName("동일 주문 10개 동시 요청 → 1개만 성공, 9개 차단")
    void concurrentVerify_onlyOneSucceeds() throws InterruptedException {
        // Given
        Order order = createPendingOrder(10_000L);
        mockPgPaidForAny(10_000L);

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);  // 동시 시작 신호
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        List<Future<String>> futures = new ArrayList<>();

        // When: 10개 스레드 동시 요청 (컨트롤러와 동일하게 분산락 적용)
        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                startLatch.await(); // 모든 스레드 준비 완료까지 대기
                try {
                    PaymentVerifyRequest request = buildRequest(
                            "imp_concurrent_" + idx,
                            order.getOrderNo()
                    );
                    distributedLockService.executeWithPaymentLock(
                            order.getOrderNo(),
                            () -> paymentService.verifyAndSavePayment(request)
                    );
                    successCount.incrementAndGet();
                    return "SUCCESS";
                } catch (PaycoreException e) {
                    failCount.incrementAndGet();
                    return "FAIL: " + e.getErrorCode().name();
                } finally {
                    doneLatch.countDown();
                }
            }));
        }

        startLatch.countDown(); // 동시 시작
        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // Then
        assertThat(completed).isTrue();

        System.out.println("\n========================================");
        System.out.println("  [1-2] 동시성 테스트 결과");
        System.out.println("  Total Requests : " + threadCount);
        System.out.println("  Success Count  : " + successCount.get());
        System.out.println("  Blocked Count  : " + failCount.get());
        System.out.println("========================================\n");

        assertThat(successCount.get())
                .as("동일 주문에 대해 오직 1개만 결제 성공해야 합니다")
                .isEqualTo(1);

        assertThat(failCount.get())
                .as("나머지 9개는 중복/락 실패로 차단되어야 합니다")
                .isEqualTo(9);

        // DB에 Payment 레코드가 정확히 1건
        long paymentCount = paymentRepository.findAll().stream()
                .filter(p -> p.getMerchantUid().equals(order.getOrderNo()))
                .count();
        assertThat(paymentCount)
                .as("Payment 레코드는 1건만 생성되어야 합니다")
                .isEqualTo(1);

        Order finalOrder = orderRepository.findByOrderNo(order.getOrderNo()).orElseThrow();
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
