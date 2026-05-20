package com.paycore.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${cors.allowed-origins:http://localhost:3000}")
    private String allowedOrigins;

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

        // allowedOriginPatterns("*") 와 allowCredentials(true) 는 함께 사용 불가
        if (!allowedOrigins.equals("*")) {
            mapping.allowCredentials(true);
        }
    }
}
