# Commerce Payment Orchestrator

단순 PG 연동이 아닌 **결제 정합성과 장애 복구**를 고려한 실무형 결제 통합 모듈입니다.
Redis 분산락, Saga 패턴, 스케줄러 기반 복구를 통해 중복 결제·금액 위변조·Webhook 유실에 대응합니다.

---

## 👨‍💻 Developer

| jeonggu.kim<br />(김정현) |
|:---:|
| <a href="https://github.com/dev-jeonggu"> <img src="https://avatars.githubusercontent.com/dev-jeonggu" width=100px alt="_"/> </a> |
| <a href="https://github.com/dev-jeonggu">@dev-jeonggu</a> |

---

## 🛠️ Stack

![Spring Boot](https://img.shields.io/badge/Spring_Boot_3.2-6DB33F?style=flat&logo=Spring-Boot&logoColor=white)
![Java](https://img.shields.io/badge/Java_17-007396?style=flat&logo=OpenJDK&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-4169E1?style=flat&logo=PostgreSQL&logoColor=white)
![Redis](https://img.shields.io/badge/Redis-DC382D?style=flat&logo=Redis&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-2496ED?style=flat&logo=Docker&logoColor=white)
![Swagger](https://img.shields.io/badge/Swagger-85EA2D?style=flat&logo=Swagger&logoColor=black)

---

## ✨ 프로젝트 목적

- PG 결제 완료 후 **서버에서 직접 PG API를 재조회**하여 금액 위변조 방지 (사후 검증)
- Redis 분산락(Redisson)으로 동일 주문에 대한 **동시 중복 결제 요청을 직렬화**
- PG Webhook을 무조건 신뢰하지 않고 **재검증 + 스케줄러 이중 복구** 구조로 유실 대비
- 재고/포인트 후처리 실패 시 **Saga 보상 트랜잭션**으로 PG 자동 취소
- 모든 PG 요청/응답을 `payment_logs` 테이블에 저장하여 **장애 추적 가능한 전문 로그** 확보

---

## 🏗️ 아키텍처

```
Client (Swagger / Frontend)
          │
          ▼ POST /payments/verify
  ┌───────────────────┐
  │  Spring Boot API  │
  │  ┌─────────────┐  │
  │  │ Redis Lock  │  │  ← 동시 중복 요청 차단
  │  └─────────────┘  │
  └────────┬──────────┘
           │
     ┌─────┼──────────────────┐
     ▼     ▼                  ▼
PostgreSQL  Redis          PortOne API
  (orders   (distributed    (결제 단건 조회
  payments   lock)           / 취소)
  logs)
     ▲
     │  5분 주기
  Scheduler
  (PENDING 30분+ → 자동 복구)

결제 후처리 실패 시:
  PaymentSagaService (REQUIRES_NEW TX)
  → PortOne 취소 + DB CANCELLED
```

---

## 📁 프로젝트 구조

```
.
├── docker-compose.yml            # 전체 스택 실행 (app + postgres + redis)
├── docker-compose.infra.yml      # 인프라만 실행 (로컬 개발용)
├── Dockerfile                    # 멀티스테이지 빌드 (gradle → jre)
├── .env.example                  # 환경 변수 템플릿
└── src/
    ├── main/java/com/paycore/
    │   ├── PaycoreApplication.java       # @EnableScheduling 진입점
    │   ├── common/
    │   │   ├── exception/               # ErrorCode, GlobalExceptionHandler
    │   │   ├── response/                # ApiResponse<T>
    │   │   └── util/                    # OrderNumberGenerator
    │   ├── config/                      # JpaConfig, RedissonConfig, WebClientConfig, SwaggerConfig
    │   ├── lock/
    │   │   └── DistributedLockService.java  # Redisson tryLock(5s/10s)
    │   ├── order/
    │   │   ├── controller/              # POST /api/v1/orders
    │   │   ├── service/                 # OrderService
    │   │   ├── repository/              # OrderRepository
    │   │   └── domain/                  # Order, OrderStatus
    │   ├── payment/
    │   │   ├── controller/              # PaymentController + DTOs
    │   │   ├── service/
    │   │   │   ├── PaymentService.java      # 핵심 결제 로직
    │   │   │   ├── PaymentSagaService.java  # 보상 트랜잭션 (REQUIRES_NEW)
    │   │   │   ├── PaymentLogService.java   # 전문 로그 저장
    │   │   │   ├── InventoryService.java    # 재고 차감 (외부 연동 스텁)
    │   │   │   └── PointService.java        # 포인트 적립 (외부 연동 스텁)
    │   │   ├── repository/              # PaymentRepository, PaymentLogRepository
    │   │   ├── client/                  # PortOneClient (WebClient)
    │   │   └── domain/                  # Payment, PaymentLog, PaymentStatus
    │   └── scheduler/
    │       └── PaymentRecoveryScheduler.java  # PENDING 자동 복구
    └── test/java/com/paycore/
        ├── support/
        │   └── AbstractIntegrationTest.java  # 통합 테스트 베이스
        └── payment/
            ├── integration/             # 통합 테스트 (6개 시나리오)
            └── service/                 # 단위 테스트
```

---

## ⚙️ How to Run

### 1. Docker로 전체 스택 실행

```bash
docker-compose up --build -d
```

> PostgreSQL, Redis, Spring Boot App이 한 번에 실행됩니다.

| 서비스 | 포트 |
|--------|------|
| Spring Boot App | 8080 |
| PostgreSQL | 5432 |
| Redis | 6379 |

```bash
# 환경변수 설정 (없으면 test 키로 동작)
cp .env.example .env

# 로그 확인
docker-compose logs -f app

# 종료
docker-compose down
```

### 2. 로컬 직접 실행 (IDE + 인프라 Docker)

```bash
# 인프라만 실행
docker-compose -f docker-compose.infra.yml up -d

# 앱 실행
./gradlew bootRun
```

### Swagger UI

```
http://localhost:8080/swagger-ui/index.html
```

```bash
# 컨테이너 상태 확인
docker-compose ps
docker-compose logs -f app       # 앱 로그
docker-compose logs -f postgres  # DB 로그
```

---

## 🔄 결제 플로우

```
[1] 주문 생성
Client → POST /api/v1/orders → DB(PENDING)

[2] PG 결제창 진행
Client → PortOne SDK (merchant_uid = orderNo)

[3] 결제 완료 후 검증 (핵심)
Client → POST /api/v1/payments/verify
         → Redis Lock 획득
         → PG API 단건 조회 (클라이언트 데이터 불신)
         → 금액 검증 (불일치 시 즉시 PG 취소 + CANCELLED)
         → DB PAID 확정 (TX 커밋)
         → 재고 차감 + 포인트 적립 (실패 시 Saga 보상 취소)
         → Lock 해제

[4] Webhook (병렬 수신)
PortOne → POST /api/v1/payments/webhook
          → PG 단건 조회로 재검증 (Webhook 내용 불신)
          → 이미 PAID면 스킵 (멱등성)

[5] 장애 복구 (5분 주기)
Scheduler → PENDING + 30분 경과 주문 조회
           → PG 단건 조회
           → PAID or CANCELLED 자동 처리
```

---

## 🌐 환경 변수

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `PORTONE_IMP_KEY` | `test_imp_key` | PortOne API Key |
| `PORTONE_IMP_SECRET` | `test_imp_secret` | PortOne API Secret |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://postgres:5432/paycore` | DB 연결 URL |
| `SPRING_DATA_REDIS_HOST` | `redis` | Redis 호스트 |
| `SCHEDULER_PENDING_RECOVERY_FIXED_DELAY` | `300000` | 복구 스케줄러 주기 (ms) |

---

## 🗄️ DB 스키마

```sql
-- 주문
CREATE TABLE orders (
    id            BIGSERIAL    PRIMARY KEY,
    order_no      VARCHAR(50)  NOT NULL UNIQUE,
    user_id       BIGINT       NOT NULL,
    item_id       BIGINT       NOT NULL,
    total_amount  BIGINT       NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL
);

-- 결제
CREATE TABLE payments (
    id               BIGSERIAL    PRIMARY KEY,
    order_id         BIGINT       NOT NULL,
    imp_uid          VARCHAR(100) NOT NULL UNIQUE,
    merchant_uid     VARCHAR(100) NOT NULL,
    pay_method       VARCHAR(50),
    paid_amount      BIGINT       NOT NULL,
    cancelled_amount BIGINT       NOT NULL DEFAULT 0,
    status           VARCHAR(20)  NOT NULL DEFAULT 'PAID',
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL
);

-- 결제 전문 로그
CREATE TABLE payment_logs (
    id            BIGSERIAL    PRIMARY KEY,
    order_no      VARCHAR(100) NOT NULL,
    log_type      VARCHAR(50)  NOT NULL,
    request_body  TEXT,
    response_body TEXT,
    success       BOOLEAN      NOT NULL,
    error_message VARCHAR(500),
    created_at    TIMESTAMPTZ  NOT NULL
);
```

---

## 🧪 테스트

인프라(PostgreSQL + Redis)가 실행된 상태에서 테스트를 실행합니다.

```bash
# 인프라 실행
docker-compose -f docker-compose.infra.yml up -d

# 테스트 + JaCoCo 커버리지 리포트
./gradlew cleanTest test jacocoTestReport
```

JaCoCo HTML 리포트: `build/reports/jacoco/html/index.html`

### 테스트 결과 (13/13 PASSED)

| # | 테스트 클래스 | 시나리오 | 결과 |
|---|--------------|----------|------|
| 1-1 | `PaymentAmountFraudTest` | PG 금액 ≠ 주문 금액 → `PaymentAmountMismatchException` + PG 즉시 취소 + 주문 `CANCELLED` | PASSED |
| 1-1 | `PaymentAmountFraudTest` | 금액 일치 → 정상 `PAID` 처리 | PASSED |
| 1-2 | `PaymentConcurrentTest` | 동일 주문 10개 동시 요청 → **성공 1건, 차단 9건** | PASSED |
| 1-3 | `PaymentWebhookIdempotencyTest` | Webhook + Verify 동시 도착 → Payment 레코드 **1건만** 생성 | PASSED |
| 2-1 | `PaymentRecoveryTest` | Webhook 유실 → 스케줄러가 30분 경과 `PENDING` 주문 자동 `PAID` 처리 | PASSED |
| 2-2 | `PaymentRecoveryTest` | 재고 차감 실패 → Saga 보상 트랜잭션 → PG 취소 + 주문 `CANCELLED` | PASSED |
| 3-1 | `PaymentPartialCancelTest` | 10,000원 결제 후 3,000원 부분 취소 → 잔여 **7,000원** | PASSED |
| 3-1 | `PaymentPartialCancelTest` | 3,000원씩 2회 누적 취소 → 잔여 **4,000원** | PASSED |
| 3-1 | `PaymentPartialCancelTest` | 전액 취소 → `CANCELLED` | PASSED |
| - | `PaymentServiceTest` | impUid 중복 → `PAYMENT_ALREADY_PROCESSED` | PASSED |
| - | `PaymentServiceTest` | 금액 위변조 단위 테스트 | PASSED |
| - | `PaymentServiceTest` | Webhook 멱등성 스킵 | PASSED |
| - | `PaycoreApplicationTests` | ApplicationContext 정상 로딩 | PASSED |

#### 1-2 동시성 테스트 실측 수치

```
========================================
  [1-2] 동시성 테스트 결과
  Total Requests : 10
  Success Count  : 1
  Blocked Count  : 9
========================================
```

### JaCoCo 커버리지

| Metric | Covered | Total | Rate |
|--------|---------|-------|------|
| LINE | 212 | 358 | **59.2%** |
| BRANCH | 26 | 46 | **56.5%** |
| METHOD | 56 | 111 | **50.5%** |

| 패키지 | 커버리지 |
|--------|---------|
| `payment.service` | **82.4%** (126/153 lines) |
| `scheduler` | **75.0%** (33/44 lines) |
| `lock` | **68.8%** (11/16 lines) |
| `common.exception` | **64.6%** (31/48 lines) |

> `payment.controller`, `payment.client` 등 HTTP/외부 연동 레이어는 `WebEnvironment.NONE` + `@MockBean` 전략으로 제외. 비즈니스 핵심인 `PaymentService`는 **82.4%** 달성.
