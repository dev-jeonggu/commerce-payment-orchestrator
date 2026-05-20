package com.paycore.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 로그 기반 알람 (개발/테스트 환경)
 *
 * 운영 환경에서는 SlackAlertService로 교체:
 * - Spring Profile로 분기: @Profile("prod")
 * - Slack Incoming Webhook URL은 application-prod.yml에 주입
 * - 환경별 알람은 명시적 Profile이 의도를 더 명확하게 표현함.
 */
@Slf4j
@Component
public class LogAlertService implements AlertService {

    @Override
    public void sendCritical(String message, String orderNo, String detail) {
        log.error("[CRITICAL ALERT] {} | orderNo={} | detail={}", message, orderNo, detail);
        // TODO 운영: Slack / PagerDuty 연동
        // slackClient.sendMessage("#payments-critical", formatCritical(message, orderNo, detail));
    }

    @Override
    public void sendWarning(String message, String orderNo, String detail) {
        log.warn("[WARNING ALERT] {} | orderNo={} | detail={}", message, orderNo, detail);
        // TODO 운영: Slack #payments-warning 채널
    }
}
