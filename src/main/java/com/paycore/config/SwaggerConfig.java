package com.paycore.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
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

                                ### 핵심 기능
                                - **사전 검증**: 주문 생성 시 금액 서버 신뢰 기준 설정
                                - **사후 검증**: PG사 결제 완료 후 금액 일치 검증
                                - **Webhook 처리**: PG사 웹훅 수신 후 단건 조회로 재검증
                                - **분산락**: Redis Redisson을 활용한 중복 결제 방지
                                - **장애 복구**: 스케줄러 기반 PENDING 주문 자동 복구
                                - **보상 트랜잭션**: Saga 패턴을 활용한 결제 실패 롤백
                                """)
                        .version("v1.0.0")
                        .contact(new Contact()
                                .name("PayCore Team")
                                .email("paycore@example.com")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local Development")));
    }
}
