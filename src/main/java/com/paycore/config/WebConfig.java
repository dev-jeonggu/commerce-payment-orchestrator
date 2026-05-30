package com.paycore.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    @Value("${cors.allowed-origins:http://localhost:3000}")
    private String allowedOrigins;

    private final MerchantAuthInterceptor merchantAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(merchantAuthInterceptor)
                .addPathPatterns(
                        "/api/v1/payments/**",
                        "/api/v1/virtual-accounts/**",
                        "/api/v1/billing-keys/**"
                )
                .excludePathPatterns(
                        "/api/v1/payments/webhook/**",  // 은행 Webhook — 별도 토큰 인증
                        "/api/v1/merchants/**"           // 가맹점 등록/조회 — 인증 불필요
                );
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = allowedOrigins.equals("*")
                ? new String[]{"*"}
                : new String[]{allowedOrigins, "http://localhost:3000", "http://localhost:8080"};

        var mapping = registry.addMapping("/api/**")
                .allowedOriginPatterns(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);

        if (!allowedOrigins.equals("*")) {
            mapping.allowCredentials(true);
        }
    }
}
