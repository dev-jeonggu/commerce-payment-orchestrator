package com.paycore.support;

import com.paycore.order.domain.Order;
import com.paycore.payment.client.PortOneClient;
import com.paycore.payment.client.dto.PortOnePaymentResponse;
import com.paycore.payment.domain.Payment;
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
import static org.mockito.Mockito.mock;

/**
 * 통합 테스트 기반 클래스
 *
 * - 이미 실행 중인 docker-compose 인프라(PostgreSQL:5432, Redis:6379) 사용
 * - PortOneClient, InventoryService, PointService → @MockBean
 * - @BeforeEach에서 테스트 데이터 자동 정리
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

    protected void mockPgPaid(String impUid, long amount) {
        PortOnePaymentResponse response = mock(PortOnePaymentResponse.class);
        PortOnePaymentResponse.PaymentData data = mock(PortOnePaymentResponse.PaymentData.class);
        given(response.isSuccess()).willReturn(true);
        given(response.isPaid()).willReturn(true);
        given(response.getAmount()).willReturn(amount);
        given(response.getResponse()).willReturn(data);
        given(data.getImpUid()).willReturn(impUid);
        given(data.getPayMethod()).willReturn("card");
        given(portOneClient.getPaymentByImpUid(impUid)).willReturn(response);
    }

    protected void mockPgPaidForAny(long amount) {
        PortOnePaymentResponse response = mock(PortOnePaymentResponse.class);
        PortOnePaymentResponse.PaymentData data = mock(PortOnePaymentResponse.PaymentData.class);
        given(response.isSuccess()).willReturn(true);
        given(response.isPaid()).willReturn(true);
        given(response.getAmount()).willReturn(amount);
        given(response.getResponse()).willReturn(data);
        given(data.getImpUid()).willReturn("imp_any");
        given(data.getPayMethod()).willReturn("card");
        given(portOneClient.getPaymentByImpUid(any())).willReturn(response);
    }
}
