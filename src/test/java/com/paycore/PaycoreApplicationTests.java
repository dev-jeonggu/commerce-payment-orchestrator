package com.paycore;

import com.paycore.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Spring Context 로딩 테스트")
class PaycoreApplicationTests extends AbstractIntegrationTest {

    @Test
    @DisplayName("ApplicationContext 정상 로딩")
    void contextLoads() {
        // TestContainers (PostgreSQL + Redis) 환경에서 Spring Context 로딩 확인
    }
}
