package com.paycore.virtualaccount.domain;

/**
 * 가상계좌 상태
 *
 * ISSUED      → 계좌 발급 완료, 입금 대기
 * DEPOSITED   → 입금 확인 완료 (Webhook via PG)
 * EXPIRED     → 입금 기한 초과 (스케줄러가 ISSUED + dueDate < now 인 건 처리)
 *
 * [상태 전이]
 * ISSUED → DEPOSITED  : PG Webhook (vbank_ready → paid)
 * ISSUED → EXPIRED    : 만료 스케줄러 (dueDate 초과)
 *
 * [주의] DEPOSITED/EXPIRED 상태에서는 추가 전이 불가.
 * EXPIRED 후 사후 입금이 들어올 경우 PG사마다 처리 방식이 다름.
 * PortOne은 만료 계좌 입금 시 자동 환불처리하거나 Webhook을 보내지 않음.
 * 운영 환경에서는 PG사 정책 확인 필수.
 */
public enum VirtualAccountStatus {
    ISSUED,
    DEPOSITED,
    EXPIRED
}
