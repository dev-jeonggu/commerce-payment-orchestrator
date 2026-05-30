# Commerce Payment Orchestrator

카카오페이·토스페이먼츠 같은 **PG(Payment Gateway) 서버** 역할을 하는 결제 처리 시스템입니다.  
가맹점이 해당 API를 호출하면, 우리가 카드사·통신사·은행과 직접 연동하고 결과를 가맹점 Webhook으로 통보합니다.

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

기존 이커머스 결제 모듈(PortOne 중계)에서 **우리 자신이 PG가 되는 구조**로 전환했습니다.

| 구분 | 기존 (이커머스 관점) | 현재 (PG 관점) |
|------|---------------------|----------------|
| 역할 | PortOne을 통해 결제 | 가맹점에게 결제 수단 제공 |
| Webhook 방향 | PG → 우리 서버 수신 | 우리 서버 → 가맹점 발송 |
| 외부 연동 | PortOne API 1개 | 카드사 VAN / 통신사 / 은행 직접 |
| 핵심 도메인 | 주문(Order) | 가맹점(Merchant) |

**설계 목표**

- 가맹점 API Key(X-Merchant-Id + X-Api-Key) 인증으로 **무단 결제 요청 차단**
- 결제수단 추상화(`PaymentMethodProcessor`)로 **카드·휴대폰·가상계좌·계좌이체 확장 용이**
- 은행 입금 Webhook 내부 토큰 검증으로 **가짜 입금 확인 주입 방지**
- 가맹점 Webhook 발송 실패 시 **DLQ + 재시도 스케줄러**로 유실 방지
- Redis 분산락(Redisson)으로 동일 주문 **동시 중복 결제·취소 직렬화**
- 보상 트랜잭션(Saga)으로 결제 후처리 실패 시 **자동 환불**
- 가상계좌 만료·입금 동시 처리를 **낙관적 락(Optimistic Lock)**으로 보호
- 모든 요청/응답을 `payment_logs`에 기록하여 **장애 시 전문 추적 가능**

---

## 🏗️ 아키텍처

```
가맹점 (이커머스 서버)
    │
    │  X-Merchant-Id + X-Api-Key (API Key 인증)
    ▼
┌────────────────────────────────────────────────┐
│             Commerce Payment Gateway           │
│                                                │
│  ┌───────────────────────────────────────┐     │
│  │  MerchantAuthInterceptor              │     │  ← 모든 결제 API 보호
│  │  (X-Merchant-Id + X-Api-Key 검증)      │     │
│  └───────────────────────────────────────┘     │
│                                                │
│  ┌───────────────┐   ┌───────────────────────┐ │
│  │ PaymentService│   │ DistributedLockService│ │  ← 중복 결제 방지
│  │               │   │  (Redisson / Redis)   │ │
│  └──────┬────────┘   └───────────────────────┘ │
│         │ PaymentMethodRouter                  │
│    ┌────┴──────────────────────────────┐       │
│    ▼         ▼            ▼            ▼       │
│  [CARD]  [MOBILE]  [VIRTUAL_ACCOUNT] [BANK]    │  ← 결제수단별 처리기 (stub)
│  카드사VAN  통신사         은행채번API    뱅크페이     │
└───────────────────────┬────────────────────────┘
                        │ 결제 완료
                        ▼
              WebhookDispatcher (@Async)
              HMAC-SHA256 서명 후 가맹점 URL로 POST
                        │
              실패 시 WebhookDeadLetter 저장
              WebhookRetryScheduler (1분 주기 재시도)

은행 입금 확인:
  은행 → POST /api/v1/payments/webhook/bank (X-Internal-Token 인증)
       → VirtualAccountService.processDeposit()
       → Payment 저장 + 가맹점 Webhook 발송

장애 복구:
  PaymentRecoveryScheduler (5분 주기)
  → ISSUED 가상계좌 Webhook 누락 건 직접 조회 후 복구

Saga 보상 트랜잭션:
  결제 후처리 실패 → PaymentSagaService (REQUIRES_NEW TX)
  → 결제 취소 + Payment CANCELLED
  → 실패 시 SagaDeadLetter 저장 → DeadLetterRetryScheduler (1분 주기)
```

---

## 📁 프로젝트 구조

```
src/main/java/com/paycore/
├── PaycoreApplication.java          # @EnableScheduling @EnableAsync
├── merchant/                        # 가맹점 관리 (핵심 도메인)
│   ├── domain/Merchant.java         # merchantId, secretKey, webhookUrl, webhookSecret
│   ├── service/MerchantService.java
│   ├── repository/MerchantRepository.java
│   └── controller/MerchantController.java
├── payment/
│   ├── domain/
│   │   ├── Payment.java             # txId, merchantId, merchantOrderId, paymentMethod
│   │   ├── PaymentStatus.java       # PAID / PARTIAL_CANCELLED / CANCELLED
│   │   └── PaymentLog.java          # 모든 요청·응답 전문 저장
│   ├── method/                      # 결제수단 추상화
│   │   ├── PaymentMethod.java       # CARD / MOBILE / VIRTUAL_ACCOUNT / BANK_TRANSFER
│   │   ├── PaymentMethodProcessor.java  # 공통 인터페이스
│   │   ├── PaymentMethodRouter.java     # PaymentMethod → Processor 라우팅
│   │   ├── card/CardPaymentProcessor.java
│   │   ├── mobile/MobilePaymentProcessor.java
│   │   ├── virtualaccount/VirtualAccountMethodProcessor.java
│   │   ├── transfer/BankTransferProcessor.java
│   │   └── cmd/                     # Command / Result DTO
│   │       ├── PaymentCommand.java / PaymentDetail.java
│   │       ├── CancelCommand.java / CancelResult.java
│   │       ├── BillingCommand.java / BillingResult.java
│   │       └── VirtualAccountCommand.java / VirtualAccountResult.java
│   ├── service/
│   │   ├── PaymentService.java      # 결제 요청·취소·조회·입금 Webhook 처리
│   │   ├── PaymentSagaService.java  # 보상 트랜잭션 (REQUIRES_NEW)
│   │   └── PaymentLogService.java   # 전문 로그 저장 (REQUIRES_NEW, 예외 흡수)
│   ├── repository/
│   │   ├── PaymentRepository.java
│   │   └── PaymentLogRepository.java
│   └── controller/
│       ├── PaymentController.java
│       └── dto/
│           ├── PaymentRequest.java  # merchantId, merchantOrderId, amount, paymentMethod
│           ├── PaymentResponse.java
│           └── PaymentCancelRequest.java
├── billing/                         # 빌링키 (자동결제/정기결제)
│   ├── domain/BillingKey.java       # pgBillingKey: AES-256 암호화 저장
│   ├── crypto/
│   │   ├── AES256Converter.java     # JPA AttributeConverter (투명 암복호화)
│   │   └── AES256KeyInitializer.java
│   ├── service/BillingKeyService.java
│   └── controller/BillingKeyController.java
├── virtualaccount/                  # 가상계좌 발급·입금 확인·만료 처리
│   ├── domain/VirtualAccount.java   # @Version 낙관적 락 (만료 vs 입금 경쟁 방지)
│   ├── service/VirtualAccountService.java
│   ├── scheduler/
│   │   ├── VirtualAccountExpiryScheduler.java   # 10분 주기 만료 처리
│   │   └── VirtualAccountExpiryProcessor.java   # REQUIRES_NEW 건별 독립 TX
│   └── controller/VirtualAccountController.java
├── webhook/                         # 가맹점 Webhook 발송 (방향: 우리 → 가맹점)
│   ├── WebhookDispatcher.java       # @Async, HMAC-SHA256 서명, 실패 시 DLQ 저장
│   ├── dto/WebhookPayload.java
│   ├── domain/WebhookDeadLetter.java       # 발송 실패 저장 (최대 5회 재시도)
│   ├── repository/WebhookDeadLetterRepository.java
│   ├── service/WebhookRetryService.java
│   └── scheduler/WebhookRetryScheduler.java  # 1분 주기 재시도
├── saga/                            # 보상 트랜잭션 DLQ
│   ├── domain/SagaDeadLetter.java
│   ├── service/SagaDeadLetterService.java
│   └── scheduler/DeadLetterRetryScheduler.java  # 1분 주기 재시도
├── scheduler/                       # 결제 복구 스케줄러
│   ├── PaymentRecoveryScheduler.java  # 5분 주기 가상계좌 Webhook 누락 복구
│   └── PaymentRecoveryService.java
├── lock/DistributedLockService.java   # Redisson 분산락 (waitTime=5s, leaseTime=10s)
├── notification/
│   ├── AlertService.java            # sendCritical / sendWarning
│   └── LogAlertService.java         # 현재: 로그 기반 (운영: Slack/PagerDuty 연동)
├── config/
│   ├── MerchantAuthInterceptor.java # 가맹점 API Key 인증 인터셉터
│   ├── WebConfig.java               # CORS + 인터셉터 등록
│   ├── JpaConfig.java               # @EnableJpaAuditing
│   ├── RedissonConfig.java
│   ├── WebClientConfig.java         # Webhook 발송용 WebClient
│   └── SwaggerConfig.java
└── common/
    ├── exception/
    │   ├── ErrorCode.java
    │   ├── PaycoreException.java
    │   └── GlobalExceptionHandler.java
    └── response/ApiResponse.java
```

---

## ⚙️ How to Run

### 1. Docker로 전체 스택 실행

```bash
cp .env.example .env      # 환경변수 파일 생성
docker-compose up --build -d
```

| 서비스 | 포트 |
|--------|------|
| Spring Boot App | 8080 |
| PostgreSQL | 5432 |
| Redis | 6379 |

```bash
docker-compose logs -f app   # 앱 로그 확인
docker-compose down          # 종료
```

### 2. 로컬 직접 실행 (IDE + 인프라 Docker)

```bash
# 인프라만 실행
docker-compose -f docker-compose.infra.yml up -d

# 앱 실행 (dev 프로파일 기본값)
./gradlew bootRun
```

### 3. 운영 환경 실행

```bash
export DB_URL=jdbc:postgresql://<host>:5432/paycore
export DB_USERNAME=<user>
export DB_PASSWORD=<password>
export REDIS_HOST=<redis-host>
export ENCRYPTION_AES_KEY=<32-byte-base64-key>
export INTERNAL_WEBHOOK_TOKEN=<strong-random-token>

java -jar app.jar --spring.profiles.active=prod
```

### Swagger UI

```
http://localhost:8080/swagger-ui/index.html
```

---

## 🔄 결제 플로우

### 카드·휴대폰·계좌이체

```
[1] 결제 요청
가맹점 → POST /api/v1/payments
         헤더: X-Merchant-Id, X-Api-Key
         바디: { merchantOrderId, amount, paymentMethod, orderName }

[2] 인증 및 처리
→ MerchantAuthInterceptor: merchantId + secretKey 검증
→ DistributedLockService: 동일 merchantOrderId 동시 요청 직렬화 (Redis Lock)
→ PaymentMethodRouter → CardPaymentProcessor (카드사 VAN 연동)
→ Payment 저장 (status: PAID)
→ PaymentLog 저장

[3] 가맹점 Webhook 발송 (비동기)
→ WebhookDispatcher.dispatch() (@Async)
   POST {merchant.webhookUrl}
   헤더: X-Paycore-Signature: HMAC-SHA256(webhookSecret, body)
   바디: { txId, merchantOrderId, status: "paid", amount, paymentMethod, paidAt }
→ 실패 시 WebhookDeadLetter 저장 → 1분 주기 재시도 (최대 5회)

[4] 결제 취소
가맹점 → POST /api/v1/payments/cancel
         헤더: X-Merchant-Id, X-Api-Key
→ 취소 가능 금액 검증 (paidAmount - cancelledAmount)
→ 전액 취소: status = CANCELLED
→ 부분 취소: status = PARTIAL_CANCELLED
```

### 가상계좌

```
[1] 가상계좌 발급
가맹점 → POST /api/v1/virtual-accounts
         헤더: X-Merchant-Id, X-Api-Key
→ VirtualAccountMethodProcessor.issueVirtualAccount() (은행 채번 API)
→ VirtualAccount 저장 (status: ISSUED)
→ 응답: { bankCode, bankName, accountNumber, holderName, dueDate }

[2] 고객이 가상계좌에 입금

[3] 은행 입금 확인 Webhook 수신 (내부)
은행 → POST /api/v1/payments/webhook/bank
        헤더: X-Internal-Token (고정 토큰 검증)
        파람: txId, merchantOrderId
→ VirtualAccount.markAsDeposited()
→ Payment 생성 (status: PAID)
→ WebhookDispatcher: 가맹점에 "paid" 이벤트 발송

[4] 만료 처리 (스케줄러, 10분 주기)
→ ISSUED + dueDate 경과 → VirtualAccount.markAsExpired()
→ 낙관적 락: 입금 Webhook 동시 도착 시 충돌 감지 (입금 처리 우선)

[5] Webhook 누락 복구 (스케줄러, 5분 주기)
→ ISSUED 상태 가상계좌 조회 → 은행 API 직접 조회 → 입금 확인 시 processDeposit()
```

### 빌링키 결제 (자동결제)

```
[1] 빌링키 등록
가맹점 → POST /api/v1/billing-keys
         { merchantId, userId, pgBillingKey(암호화 저장), maskedCardNo }

[2] 빌링키 결제 (구독 갱신 등)
가맹점 → POST /api/v1/billing-keys/charge
         { merchantId, userId, billingKeyId, merchantOrderId, amount, orderName }
→ AES-256 복호화 → CardPaymentProcessor.chargeBilling()
→ Payment 저장
→ WebhookDispatcher: 가맹점에 "paid" 이벤트 발송
```

---

## 🔐 보안 설계

### 가맹점 API Key 인증

모든 결제 API는 `MerchantAuthInterceptor`를 통과합니다.

```http
POST /api/v1/payments
X-Merchant-Id: A010084434
X-Api-Key: {가맹점 등록 시 발급된 secretKey}
```

| 실패 케이스 | 응답 |
|------------|------|
| 헤더 누락 | 401 |
| 존재하지 않는 merchantId | 401 |
| secretKey 불일치 | 401 |
| 정지(SUSPENDED) 가맹점 | 401 |

제외 경로: `/api/v1/payments/webhook/**` (별도 내부 토큰), `/api/v1/merchants/**`

### 은행 Webhook 내부 인증

```http
POST /api/v1/payments/webhook/bank?txId=VA-xxx&merchantOrderId=ORD-001
X-Internal-Token: {INTERNAL_WEBHOOK_TOKEN 환경변수}
```

운영 환경에서는 네트워크 레벨(방화벽/API Gateway)로 추가 보호 권장.

### 가맹점 Webhook 서명 검증

가맹점이 수신한 Webhook의 위변조 여부를 검증할 수 있습니다.

```
X-Paycore-Signature: HMAC-SHA256(webhookSecret, requestBody)
```

```java
// 가맹점 서버에서 검증 예시
Mac mac = Mac.getInstance("HmacSHA256");
mac.init(new SecretKeySpec(webhookSecret.getBytes(), "HmacSHA256"));
String expected = HexFormat.of().formatHex(mac.doFinal(requestBody.getBytes()));
assert expected.equals(request.getHeader("X-Paycore-Signature"));
```

### 빌링키 암호화

빌링키(카드사 customer_uid)는 AES-256-CBC로 암호화하여 DB에 저장합니다.

```
저장 형식: Base64(랜덤IV(16byte) + 암호문)
복호화:    JPA @Convert(AES256Converter) — 서비스 코드 투명
로그 출력: 금지 (getDecryptedPgBillingKey() 결과값)
```

---

## 🌐 API 엔드포인트

| 메서드 | 경로 | 인증 | 설명 |
|--------|------|------|------|
| POST | `/api/v1/merchants` | 없음 | 가맹점 등록 |
| GET | `/api/v1/merchants/{merchantId}` | 없음 | 가맹점 조회 |
| POST | `/api/v1/payments` | API Key | 결제 요청 (카드·휴대폰·계좌이체) |
| POST | `/api/v1/payments/cancel` | API Key | 결제 취소 (전액·부분) |
| GET | `/api/v1/payments/{merchantOrderId}` | API Key | 결제 조회 |
| POST | `/api/v1/payments/webhook/bank` | Internal Token | 은행 입금 확인 (내부용) |
| POST | `/api/v1/virtual-accounts` | API Key | 가상계좌 발급 |
| GET | `/api/v1/virtual-accounts/{merchantOrderId}` | API Key | 가상계좌 조회 |
| POST | `/api/v1/billing-keys` | API Key | 빌링키 등록 |
| GET | `/api/v1/billing-keys` | API Key | 빌링키 목록 조회 |
| POST | `/api/v1/billing-keys/charge` | API Key | 빌링키 결제 |
| DELETE | `/api/v1/billing-keys/{id}` | API Key | 빌링키 삭제 |

---

## 🌐 환경 변수

### 공통 필수

| 변수 | 설명 |
|------|------|
| `ENCRYPTION_AES_KEY` | AES-256 키 (32바이트 Base64, 미설정 시 기동 실패) |

### 보안 (운영 환경 필수)

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `INTERNAL_WEBHOOK_TOKEN` | `dev-internal-webhook-token-change-in-prod` | 은행 Webhook 인증 토큰 |

> ⚠️ 운영 배포 시 기본값 반드시 교체

### prod 프로파일 전용

| 변수 | 설명 |
|------|------|
| `DB_URL` | `jdbc:postgresql://<host>:5432/paycore` |
| `DB_USERNAME` | DB 사용자명 |
| `DB_PASSWORD` | DB 비밀번호 |
| `REDIS_HOST` | Redis 호스트 |
| `REDIS_PORT` | Redis 포트 (기본값: `6379`) |

---

## 🗄️ DB 스키마

```sql
-- 가맹점
CREATE TABLE merchants (
    id             BIGSERIAL    PRIMARY KEY,
    merchant_id    VARCHAR(50)  NOT NULL UNIQUE,  -- CPID (A010084434)
    secret_key     VARCHAR(100) NOT NULL,          -- API Key 인증용
    webhook_url    VARCHAR(500) NOT NULL,
    webhook_secret VARCHAR(200) NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at     TIMESTAMPTZ  NOT NULL
);

-- 결제
CREATE TABLE payments (
    id                 BIGSERIAL    PRIMARY KEY,
    merchant_id        VARCHAR(50)  NOT NULL,
    tx_id              VARCHAR(100) NOT NULL UNIQUE,  -- 내부 트랜잭션 ID
    merchant_order_id  VARCHAR(100) NOT NULL,
    payment_method     VARCHAR(30)  NOT NULL,         -- CARD / MOBILE / VIRTUAL_ACCOUNT / BANK_TRANSFER
    paid_amount        BIGINT       NOT NULL,
    cancelled_amount   BIGINT       NOT NULL DEFAULT 0,
    status             VARCHAR(30)  NOT NULL DEFAULT 'PAID',
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ
);
CREATE INDEX idx_payment_merchant_order ON payments (merchant_order_id);

-- 결제 전문 로그
CREATE TABLE payment_logs (
    id                BIGSERIAL    PRIMARY KEY,
    merchant_order_id VARCHAR(100) NOT NULL,
    log_type          VARCHAR(50)  NOT NULL,  -- PAYMENT_VERIFY / PAYMENT_CANCEL / WEBHOOK / SCHEDULER_RECOVERY
    request_body      TEXT,
    response_body     TEXT,
    success           BOOLEAN      NOT NULL,
    error_message     VARCHAR(500),
    created_at        TIMESTAMPTZ  NOT NULL
);

-- 빌링키
CREATE TABLE billing_keys (
    id             BIGSERIAL    PRIMARY KEY,
    merchant_id    VARCHAR(50)  NOT NULL,
    user_id        BIGINT       NOT NULL,
    pg_billing_key VARCHAR(500) NOT NULL,  -- AES-256 암호화
    masked_card_no VARCHAR(20),
    card_company   VARCHAR(50),
    is_default     BOOLEAN      NOT NULL DEFAULT false,
    deleted        BOOLEAN      NOT NULL DEFAULT false,
    created_at     TIMESTAMPTZ  NOT NULL,
    deleted_at     TIMESTAMPTZ
);

-- 가상계좌
CREATE TABLE virtual_accounts (
    id                BIGSERIAL    PRIMARY KEY,
    merchant_order_id VARCHAR(100) NOT NULL UNIQUE,
    tx_id             VARCHAR(100) NOT NULL UNIQUE,
    merchant_id       VARCHAR(50)  NOT NULL,
    bank_code         VARCHAR(10)  NOT NULL,
    bank_name         VARCHAR(50)  NOT NULL,
    account_number    VARCHAR(30)  NOT NULL,
    holder_name       VARCHAR(50)  NOT NULL,
    amount            BIGINT       NOT NULL,
    due_date          TIMESTAMPTZ  NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'ISSUED',
    version           BIGINT       NOT NULL DEFAULT 0,  -- 낙관적 락
    created_at        TIMESTAMPTZ  NOT NULL,
    deposited_at      TIMESTAMPTZ,
    expired_at        TIMESTAMPTZ
);

-- Webhook Dead Letter Queue
CREATE TABLE webhook_dead_letters (
    id                BIGSERIAL    PRIMARY KEY,
    merchant_id       VARCHAR(50)  NOT NULL,
    tx_id             VARCHAR(100),
    merchant_order_id VARCHAR(100),
    webhook_url       VARCHAR(500) NOT NULL,
    webhook_secret    VARCHAR(200) NOT NULL,
    payload           TEXT         NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    attempt_count     INT          NOT NULL DEFAULT 0,
    last_error_message VARCHAR(1000),
    last_attempted_at TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL
);

-- Saga Dead Letter Queue
CREATE TABLE saga_dead_letters (
    id                BIGSERIAL    PRIMARY KEY,
    merchant_order_id VARCHAR(100) NOT NULL,
    tx_id             VARCHAR(100),
    payment_method    VARCHAR(30),
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    error_message     VARCHAR(2000),
    attempt_count     INT          NOT NULL DEFAULT 0,
    last_attempted_at TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL
);
```

---

## ⏱️ 스케줄러 구성

| 스케줄러 | 주기 | 역할 |
|----------|------|------|
| `VirtualAccountExpiryScheduler` | 10분 | ISSUED + 만료 기한 경과 가상계좌 → EXPIRED |
| `PaymentRecoveryScheduler` | 5분 | ISSUED 가상계좌 Webhook 누락 복구 |
| `WebhookRetryScheduler` | 1분 | 가맹점 Webhook 발송 실패 재시도 (최대 5회) |
| `DeadLetterRetryScheduler` | 1분 | Saga 보상 취소 실패 재시도 (최대 5회) |

모든 스케줄러는 Redisson 분산 락으로 다중 인스턴스 환경에서 중복 실행을 방지합니다.

---

## 🧪 테스트

```bash
# 인프라 실행 (TestContainers 미사용 시)
docker-compose -f docker-compose.infra.yml up -d

# 전체 테스트
./gradlew cleanTest test

# 단위 테스트만
./gradlew test --tests "com.paycore.payment.domain.*"
```

| 테스트 | 설명 |
|--------|------|
| `PaycoreApplicationTests` | Spring Context 로딩 (TestContainers: PostgreSQL + Redis) |
| `PaymentDomainTest` | Payment 취소 상태 전이 (전액·부분·이중 취소 방지) |
