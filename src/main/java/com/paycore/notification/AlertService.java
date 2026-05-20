package com.paycore.notification;

/**
 * 알람 서비스 인터페이스
 *
 * 구현체: LogAlertService (현재), SlackAlertService (추후)
 * 운영 환경에서는 Slack Incoming Webhook 또는 PagerDuty 연동 권장.
 */
public interface AlertService {

    /**
     * 즉각적인 운영 개입이 필요한 치명적 알람
     * 예: Saga 보상 취소 실패, 금액 불일치 자동 취소 실패
     */
    void sendCritical(String message, String orderNo, String detail);

    /**
     * 확인이 필요하지만 즉각 대응 불필요한 경고
     */
    void sendWarning(String message, String orderNo, String detail);
}
