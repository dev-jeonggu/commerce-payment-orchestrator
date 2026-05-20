FROM gradle:8.5-jdk17 AS builder
WORKDIR /app
COPY build.gradle .
COPY settings.gradle .
# 의존성 캐싱 레이어 분리 (재빌드 시 빠름)
RUN gradle dependencies --no-daemon || true
COPY src src
RUN gradle bootJar --no-daemon -x test

FROM eclipse-temurin:17-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
