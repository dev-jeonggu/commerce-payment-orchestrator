package com.paycore.support;

import com.paycore.order.domain.Order;
import com.paycore.payment.client.PortOneClient;
import com.paycore.payment.domain.Payment;
import com.paycore.payment.pg.PgCancelResult;
import com.paycore.payment.pg.PgPaymentDetail;
import com.paycore.payment.repository.PaymentLogRepository;
import com.paycore.payment.repository.PaymentRepository;
import com.paycore.order.repository.OrderRepository;
import com.paycore.payment.service.InventoryService;
import com.paycore.payment.service.PointService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * 통합 테스트 기반 클래스
 *
 * - 이미 실행 중인 docker-compose 인프라(PostgreSQL:5432, Redis:6379) 사용
 * - PortOneClient, InventoryService, PointService → @MockBean
 * - @BeforeEach에서 테스트 데이터 자동 정리
 *
 * [변경] PortOneClient 메서드명 변경 반영
 *   - getPaymentByImpUid() → @Deprecated 유지 (하위 호환)
 *   - getPaymentByPaymentKey() → PgRouter 경유 실제 호출 메서드
 *   - cancelPayment() → @Deprecated 유지 (하위 호환)
 *   - cancel() → PgRouter 경유 실제 호출 메서드
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    @MockBean
    protected PortOneClient portOneClient;

    @MockBean
    protected InventoryService inventoryService;

    @MockBean
    protected PointService pointService;

    @Autowired
    protected OrderRepository orderRepository;

    @Autowired
    protected PaymentRepository paymentRepository;

    @Autowired
    protected PaymentLogRepository paymentLogRepository;

    private static final AtomicInteger orderSeq = new AtomicInteger(100);

    @BeforeEach
    void cleanUp() {
        paymentLogRepository.deleteAll();
        paymentRepository.deleteAll();
        orderRepository.deleteAll();
    }

    // ─── 테스트 헬퍼 ────────────────────────────────────────────────

    protected Order createPendingOrder(long amount) {
        Order order = Order.builder()
                .orderNo("ORD-TEST-" + String.format("%03d", orderSeq.incrementAndGet()))
                .userId(1L)
                .itemId(10L)
                .totalAmount(amount)
                .build();
        return orderRepository.save(order);
    }

    protected Payment createPaidPayment(Order order, String impUid) {
        order.markAsPaid();
        orderRepository.save(order);
        Payment payment = Payment.builder()
                .orderId(order.getId())
                .impUid(impUid)
                .merchantUid(order.getOrderNo())
                .payMethod("card")
                .paidAmount(order.getTotalAmount())
                .build();
        return paymentRepository.save(payment);
    }

    /**
     * PG 결제 완료 응답 mock
     * getPaymentByPaymentKey(impUid) 와 하위 호환 메서드 둘 다 stub
     */
    protected void mockPgPaid(String impUid, long amount) {
        PgPaymentDetail detail = buildPgDetail(impUid, amount, "paid");

        // PgRouter 경유 호출 (실제 서비스 코드 경로)
        given(portOneClient.getPaymentByPaymentKey(impUid)).willReturn(detail);

        // 스케줄러 복구용 merchant_uid 기반 조회
        given(portOneClient.getPaymentByOrderId(any())).willReturn(detail);

        // 기본 cancel stub (예외 없이 성공)
        given(portOneClient.cancel(any())).willReturn(
                PgCancelResult.of(impUid, amount, 0L));
    }

    protected void mockPgPaidForAny(long amount) {
        PgPaymentDetail detail = buildPgDetail("imp_any", amount, "paid");

        given(portOneClient.getPaymentByPaymentKey(any())).willReturn(detail);
        given(portOneClient.getPaymentByOrderId(any())).willReturn(detail);
        given(portOneClient.cancel(any())).willReturn(
                PgCancelResult.of("imp_any", amount, 0L));
    }

    private PgPaymentDetail buildPgDetail(String paymentKey, long amount, String status) {
        return PgPaymentDetail.builder()
                .paymentKey(paymentKey)
                .orderId("any-order")
                .status(status)
                .payMethod("card")
                .amount(amount)
                .cancelledAmount(0L)
                .build();
    }
}
