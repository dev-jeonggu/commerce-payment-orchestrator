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
 * [논란] 런타임에 라우팅하면 컴파일 타임 안전성이 없지 않나?
 *   → PgProvider enum과 구현체 등록이 함께 변경되어야 하므로 실수 가능.
 *   → 해결: route() 호출 시 미등록 provider면 즉시 예외 발생으로 빠른 피드백.
 *   → 통합 테스트에서 모든 enum 값에 대한 라우팅 검증 추가 권장.
 *
 * [논란] null pgProvider 처리
 *   → 기존 데이터 호환성: pgProvider가 NULL인 레코드는 PORTONE으로 fallback.
 *   → 신규 주문은 반드시 pgProvider 설정.
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
