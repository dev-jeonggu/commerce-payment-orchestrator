package com.paycore.payment.domain;

public enum PaymentStatus {
    PAID,       // 결제 완료
    CANCELLED,  // 전액 취소
    PARTIAL_CANCELLED  // 부분 취소
}
