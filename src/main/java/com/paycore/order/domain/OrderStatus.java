package com.paycore.order.domain;

public enum OrderStatus {
    PENDING,            // 주문 생성됨, 결제 대기
    PENDING_PAYMENT,    // 가상계좌 발급됨, 입금 대기
    PAID,               // 결제 완료
    CANCELLED,          // 취소됨
    FAILED              // 결제 실패
}
