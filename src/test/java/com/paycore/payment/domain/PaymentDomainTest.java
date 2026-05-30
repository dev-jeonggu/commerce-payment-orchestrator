package com.paycore.payment.domain;

import com.paycore.payment.method.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("[단위] Payment 도메인 - 취소 로직")
class PaymentDomainTest {

    private Payment payment;

    @BeforeEach
    void setUp() {
        payment = Payment.builder()
                .merchantId("TEST-MERCHANT")
                .txId("CARD-DOMAIN-001")
                .merchantOrderId("ORD-DOMAIN-001")
                .paymentMethod(PaymentMethod.CARD)
                .paidAmount(30_000L)
                .build();
    }

    @Test
    @DisplayName("초기 상태: PAID, cancelledAmount=0")
    void initialState() {
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(payment.getCancelledAmount()).isEqualTo(0L);
    }

    @Nested
    @DisplayName("전액 취소 (cancel)")
    class FullCancel {

        @Test
        @DisplayName("전액 취소 → CANCELLED, cancelledAmount = paidAmount")
        void fullCancel_statusCancelled() {
            payment.cancel(30_000L);

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
            assertThat(payment.getCancelledAmount()).isEqualTo(30_000L);
            assertThat(payment.getUpdatedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("부분 취소 (cancel)")
    class PartialCancel {

        @Test
        @DisplayName("부분 취소 → PARTIAL_CANCELLED, cancelledAmount 누적")
        void partialCancel_statusPartialCancelled() {
            payment.cancel(10_000L);

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIAL_CANCELLED);
            assertThat(payment.getCancelledAmount()).isEqualTo(10_000L);
        }

        @Test
        @DisplayName("부분 취소 2회 누적 → cancelledAmount 합산")
        void partialCancel_twice_accumulatesCancelledAmount() {
            payment.cancel(10_000L);
            payment.cancel(10_000L);

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIAL_CANCELLED);
            assertThat(payment.getCancelledAmount()).isEqualTo(20_000L);
        }

        @Test
        @DisplayName("부분 취소 누적이 전액과 일치하면 → CANCELLED")
        void partialCancel_sumEqualsPaidAmount_statusCancelled() {
            payment.cancel(10_000L);
            payment.cancel(20_000L);

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
            assertThat(payment.getCancelledAmount()).isEqualTo(30_000L);
        }
    }
}
