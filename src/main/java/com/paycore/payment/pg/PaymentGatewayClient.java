package com.paycore.payment.pg;

/**
 * PG사 연동 공통 인터페이스
 *
 * [설계 의도] PG사마다 다른 API 스펙을 이 인터페이스가 흡수.
 * 서비스 레이어는 PgRouter를 통해 이 인터페이스만 바라봄.
 * 새 PG사 추가 = 이 인터페이스 구현체 1개 추가 + PgProvider enum 추가.
 *
 * 모든 PG가 빌링키/가상계좌를 지원하지 않으므로(예: 카카오페이는 VA 미지원)
 * default 메서드로 UnsupportedOperationException을 던짐.
 * 구현체가 지원하는 기능만 override하며, 호출 전 지원 여부 확인 필요.
 */
public interface PaymentGatewayClient {

    PgProvider provider();

    /**
     * 결제 단건 조회 (imp_uid / paymentKey 기준)
     */
    PgPaymentDetail getPaymentByPaymentKey(String paymentKey);

    /**
     * 주문번호(merchantUid)로 결제 단건 조회
     * 스케줄러 복구 등 paymentKey를 모를 때 사용
     */
    PgPaymentDetail getPaymentByOrderId(String orderId);

    /**
     * 결제 취소 (전액/부분)
     */
    PgCancelResult cancel(PgCancelCommand command);

    /**
     * 빌링키 결제 (자동결제/정기결제)
     * 미지원 PG는 UnsupportedOperationException 발생
     */
    default PgBillingResult chargeBilling(PgBillingCommand command) {
        throw new UnsupportedOperationException(provider() + " does not support billing key payment");
    }

    /**
     * 가상계좌 발급
     * 미지원 PG는 UnsupportedOperationException 발생
     */
    default PgVirtualAccountResult issueVirtualAccount(PgVirtualAccountCommand command) {
        throw new UnsupportedOperationException(provider() + " does not support virtual account");
    }
}
