package com.paycore.payment.pg;

/**
 * 지원하는 PG사 목록
 *
 * 새 PG사 추가 시: enum 값 추가 + PaymentGatewayClient 구현체 작성 + PgRouter 자동 등록
 */
public enum PgProvider {
    PORTONE,    // PortOne (구 아이포트) - 현재 주력 PG
    TOSS,       // 토스페이먼츠 - 추후 확장
    KAKAO       // 카카오페이 - 추후 확장
}
