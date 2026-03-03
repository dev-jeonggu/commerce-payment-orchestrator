package com.paycore.order.domain;

public enum OrderStatus {
    PENDING,    // 결제 대기
    PAID,       // 결제 완료
    CANCELLED,  // 취소됨
    FAILED      // 결제 실패
}
