package com.paycore.payment.pg;

import com.paycore.common.exception.ErrorCode;
import com.paycore.common.exception.PaycoreException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * PG사 라우터
 *
 * Spring이 PaymentGatewayClient 구현체 목록을 자동 주입.
 * 결제 시 pgProvider 값으로 적절한 클라이언트를 선택.
 *
 * route() 호출 시 미등록 provider면 즉시 예외 발생으로 빠른 피드백.
 * null pgProvider는 기존 데이터 호환성을 위해 PORTONE으로 fallback 처리.
 * 신규 주문은 반드시 pgProvider를 설정해야 함.
 */
@Slf4j
@Component
public class PgRouter {

    private final Map<PgProvider, PaymentGatewayClient> clientMap;

    public PgRouter(List<PaymentGatewayClient> clients) {
        this.clientMap = clients.stream()
                .collect(Collectors.toMap(PaymentGatewayClient::provider, Function.identity()));
        log.info("[PgRouter] 등록된 PG 클라이언트: {}", clientMap.keySet());
    }

    public PaymentGatewayClient route(PgProvider provider) {
        PgProvider resolved = Optional.ofNullable(provider).orElse(PgProvider.PORTONE);
        PaymentGatewayClient client = clientMap.get(resolved);
        if (client == null) {
            throw new PaycoreException(ErrorCode.PG_API_ERROR,
                    "지원하지 않는 PG사: " + resolved);
        }
        return client;
    }
}
