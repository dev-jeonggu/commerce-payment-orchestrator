package com.paycore.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Commerce Payment Orchestrator API")
                        .description("""
                                ## 이커머스 결제 통합 오케스트레이터

                                ### 결제 플로우
                                ```
                                1. POST /api/v1/orders          → 주문 생성 (PENDING)
                                2. [클라이언트] PG 결제창 진행
                                3. POST /api/v1/payments/verify → 결제 검증 (PAID 확정)
                                4. POST /api/v1/payments/cancel → 결제 취소 (필요 시)
                                ```

                                ### 핵심 기술 포인트
                                | 기능 | 설명 |
                                |------|------|
                                | **금액 위변조 방지** | 클라이언트 금액을 신뢰하지 않고 PG API 단건 조회로 재검증 |
                                | **중복 결제 방지** | Redis Redisson 분산락으로 동일 주문 동시 요청 직렬화 |
                                | **취소 동시성 방지** | 취소 요청에도 동일 분산락 적용 |
                                | **장애 복구** | 스케줄러(5분 주기)로 PENDING 주문 자동 복구 |
                                | **보상 트랜잭션** | Saga 패턴 - 재고/포인트 처리 실패 시 결제 자동 취소 |
                                | **Webhook 멱등성** | 중복 수신 시 DB 상태 확인 후 스킵 |

                                ### 에러 코드 일람
                                | HTTP | 의미 |
                                |------|------|
                                | 400  | 금액 불일치 / 입력값 오류 |
                                | 404  | 주문·결제 정보 없음 |
                                | 409  | 중복 결제 / 분산락 획득 실패 |
                                | 502  | PG사 API 오류 |
                                """)
                        .version("v1.0.0")
                        .contact(new Contact()
                                .name("PayCore Team")
                                .email("paycore@example.com")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local (dev)"),
                        new Server().url("https://api.paycore.com").description("Production")))
                .tags(List.of(
                        new Tag().name("Order API").description("주문 생성 및 관리"),
                        new Tag().name("Payment API").description("결제 검증 · 취소 · 조회 · Webhook")));
    }
}
