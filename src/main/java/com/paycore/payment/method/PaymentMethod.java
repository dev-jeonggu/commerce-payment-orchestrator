package com.paycore.payment.method;

public enum PaymentMethod {
    CARD,            // 신용/체크카드 → 카드사 VAN 연동
    MOBILE,          // 휴대폰 소액결제 → SKT/KT/LGU+ 통신사 연동
    VIRTUAL_ACCOUNT, // 가상계좌 → 은행 채번 API 연동
    BANK_TRANSFER    // 계좌이체 → 뱅크페이(금융결제원) 연동
}
