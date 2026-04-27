package com.paycore.order.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("[단위] Order 도메인 - 상태 전이")
class OrderDomainTest {

    private Order order;

    @BeforeEach
    void setUp() {
        order = Order.builder()
                .orderNo("ORD-DOMAIN-001")
                .userId(1L)
                .itemId(10L)
                .totalAmount(30_000L)
                .build();
    }

    @Test
    @DisplayName("신규 주문 초기 상태는 PENDING")
    void newOrder_isPending() {
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.isPending()).isTrue();
        assertThat(order.isPaid()).isFalse();
    }

    @Nested
    @DisplayName("markAsPaid()")
    class MarkAsPaid {

        @Test
        @DisplayName("PENDING → PAID 정상 전이")
        void pendingToPaid_success() {
            order.markAsPaid();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
            assertThat(order.isPaid()).isTrue();
            assertThat(order.getUpdatedAt()).isNotNull();
        }

        @Test
        @DisplayName("이미 PAID 상태에서 재호출 → 예외")
        void alreadyPaid_throws() {
            order.markAsPaid();
            assertThatThrownBy(order::markAsPaid)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("PAID");
        }

        @Test
        @DisplayName("CANCELLED 상태에서 PAID 전이 시도 → 예외")
        void cancelledToPaid_throws() {
            order.markAsCancelled();
            assertThatThrownBy(order::markAsPaid)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("markAsCancelled()")
    class MarkAsCancelled {

        @Test
        @DisplayName("PENDING → CANCELLED 정상 전이")
        void pendingToCancelled_success() {
            order.markAsCancelled();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order.getUpdatedAt()).isNotNull();
        }

        @Test
        @DisplayName("PAID → CANCELLED 정상 전이")
        void paidToCancelled_success() {
            order.markAsPaid();
            order.markAsCancelled();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        }

        @Test
        @DisplayName("이미 CANCELLED 상태에서 재호출 → 예외")
        void alreadyCancelled_throws() {
            order.markAsCancelled();
            assertThatThrownBy(order::markAsCancelled)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("이미 취소된 주문");
        }
    }

    @Nested
    @DisplayName("markAsFailed()")
    class MarkAsFailed {

        @Test
        @DisplayName("PENDING → FAILED 정상 전이")
        void pendingToFailed_success() {
            order.markAsFailed();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
        }
    }
}
