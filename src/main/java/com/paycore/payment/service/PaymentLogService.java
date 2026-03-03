package com.paycore.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paycore.payment.domain.PaymentLog;
import com.paycore.payment.repository.PaymentLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 전문 로그 저장 서비스
 * 메인 트랜잭션 실패와 무관하게 로그는 반드시 저장되어야 하므로
 * Propagation.REQUIRES_NEW를 사용
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentLogService {

    private final PaymentLogRepository paymentLogRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveLog(String orderNo, PaymentLog.LogType logType,
                        Object request, Object response, boolean success, String errorMessage) {
        try {
            String requestJson = request != null ? objectMapper.writeValueAsString(request) : null;
            String responseJson = response != null ? objectMapper.writeValueAsString(response) : null;

            PaymentLog log = PaymentLog.builder()
                    .orderNo(orderNo)
                    .logType(logType)
                    .requestPayload(requestJson)
                    .responsePayload(responseJson)
                    .success(success)
                    .errorMessage(errorMessage)
                    .build();

            paymentLogRepository.save(log);
        } catch (JsonProcessingException e) {
            log.error("[PaymentLogService] 로그 저장 실패 - orderNo: {}", orderNo, e);
        }
    }
}
