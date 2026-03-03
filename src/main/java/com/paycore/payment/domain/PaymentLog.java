package com.paycore.payment.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 결제 전문 로그 테이블
 * 실무에서는 장애 발생 시 이 테이블이 디버깅의 핵심이 됨
 * 모든 PG API 요청/응답을 JSON으로 저장
 */
@Entity
@Table(
    name = "payment_logs",
    indexes = {
        @Index(name = "idx_payment_log_order_no", columnList = "order_no"),
        @Index(name = "idx_payment_log_created", columnList = "created_at")
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class PaymentLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false)
    private String orderNo;

    @Column(name = "log_type", nullable = false)
    @Enumerated(EnumType.STRING)
    private LogType logType;

    /**
     * PG API 요청 전문 (JSON)
     */
    @Column(name = "request_payload", columnDefinition = "TEXT")
    private String requestPayload;

    /**
     * PG API 응답 전문 (JSON)
     */
    @Column(name = "response_payload", columnDefinition = "TEXT")
    private String responsePayload;

    @Column(name = "success")
    private Boolean success;

    @Column(name = "error_message")
    private String errorMessage;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public PaymentLog(String orderNo, LogType logType, String requestPayload,
                      String responsePayload, Boolean success, String errorMessage) {
        this.orderNo = orderNo;
        this.logType = logType;
        this.requestPayload = requestPayload;
        this.responsePayload = responsePayload;
        this.success = success;
        this.errorMessage = errorMessage;
    }

    public enum LogType {
        PAYMENT_VERIFY,     // 결제 검증
        PAYMENT_CANCEL,     // 결제 취소
        WEBHOOK,            // 웹훅 수신
        SCHEDULER_RECOVERY  // 스케줄러 복구
    }
}
