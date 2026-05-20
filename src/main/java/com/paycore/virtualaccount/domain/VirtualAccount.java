package com.paycore.virtualaccount.domain;

import com.paycore.payment.pg.PgProvider;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 가상계좌 엔티티
 *
 * [설계 결정]
 * - Payment 테이블과 별도로 분리: 가상계좌는 "발급 → 입금 대기 → 입금 확인" 라이프사이클을
 *   Payment와 독립적으로 추적해야 함. Payment는 입금 확인 후(DEPOSITED) 생성됨.
 *
 * - impUid는 가상계좌 발급 시점부터 존재 (PortOne 기준).
 *   Payment.impUid와 동일한 값이 되나, Payment는 DEPOSITED 이후에만 생성되므로
 *   VirtualAccount가 impUid를 독립 보관.
 *
 * [인덱스]
 * - orderNo: Webhook/조회 시 주문번호로 즉시 조회
 * - impUid: PG Webhook이 imp_uid로 전달하므로 단일 인덱스 추가
 * - status + dueDate: 만료 스케줄러가 ISSUED 건 중 기한 초과 건 배치 조회
 */
@Entity
@Table(
    name = "virtual_accounts",
    indexes = {
        @Index(name = "idx_va_order_no", columnList = "order_no"),
        @Index(name = "idx_va_imp_uid", columnList = "imp_uid"),
        @Index(name = "idx_va_status_due", columnList = "status, due_date")
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class VirtualAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 가맹점 주문번호 (= Order.orderNo)
     * Order 엔티티 직접 참조 대신 orderNo 문자열 보관 → 느슨한 결합
     */
    @Column(name = "order_no", nullable = false, unique = true)
    private String orderNo;

    /**
     * PG사 결제 키 (PortOne: imp_uid)
     * 가상계좌 발급 시점에 이미 PG사가 부여함
     */
    @Column(name = "imp_uid", nullable = false, unique = true)
    private String impUid;

    @Enumerated(EnumType.STRING)
    @Column(name = "pg_provider", nullable = false)
    private PgProvider pgProvider;

    /** 은행 코드 (ex. "004" = KB국민) */
    @Column(name = "bank_code", nullable = false, length = 10)
    private String bankCode;

    /** 은행명 (ex. "KB국민은행") */
    @Column(name = "bank_name", nullable = false, length = 50)
    private String bankName;

    /** 가상계좌 번호 */
    @Column(name = "account_number", nullable = false, length = 30)
    private String accountNumber;

    /** 예금주명 */
    @Column(name = "holder_name", nullable = false, length = 50)
    private String holderName;

    /** 결제 금액 */
    @Column(name = "amount", nullable = false)
    private Long amount;

    /** 입금 기한 */
    @Column(name = "due_date", nullable = false)
    private LocalDateTime dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VirtualAccountStatus status;

    /**
     * 낙관적 락 버전 필드
     *
     * [목적] 만료 스케줄러(EXPIRED 처리)와 입금 Webhook(DEPOSITED 처리)의 동시 실행 충돌 방지.
     *   동일 VA에 두 TX가 동시 접근 시 나중에 커밋하는 쪽이 OptimisticLockException 발생.
     *   → 스케줄러가 EXPIRED 처리 도중 입금 Webhook이 먼저 DEPOSITED로 변경하면
     *     스케줄러 TX가 충돌 → 고객이 입금했는데 EXPIRED로 덮어씌워지는 버그 방지.
     *   → 스케줄러의 OptimisticLockException은 "입금이 먼저 처리된 정상 케이스"로 간주.
     */
    @Version
    @Column(nullable = false)
    private long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "deposited_at")
    private LocalDateTime depositedAt;

    @Column(name = "expired_at")
    private LocalDateTime expiredAt;

    @Builder
    public VirtualAccount(String orderNo, String impUid, PgProvider pgProvider,
                          String bankCode, String bankName, String accountNumber,
                          String holderName, Long amount, LocalDateTime dueDate) {
        this.orderNo = orderNo;
        this.impUid = impUid;
        this.pgProvider = pgProvider != null ? pgProvider : PgProvider.PORTONE;
        this.bankCode = bankCode;
        this.bankName = bankName;
        this.accountNumber = accountNumber;
        this.holderName = holderName;
        this.amount = amount;
        this.dueDate = dueDate;
        this.status = VirtualAccountStatus.ISSUED;
    }

    /**
     * 입금 확인 처리 (ISSUED → DEPOSITED)
     *
     * [멱등성] 이미 DEPOSITED인 경우 예외 없이 skip하려면
     *   호출 전 status 체크 후 호출할 것.
     */
    public void markAsDeposited() {
        if (this.status != VirtualAccountStatus.ISSUED) {
            throw new IllegalStateException(
                    "ISSUED 상태의 가상계좌만 입금 처리 가능합니다. 현재: " + this.status);
        }
        this.status = VirtualAccountStatus.DEPOSITED;
        this.depositedAt = LocalDateTime.now();
    }

    /**
     * 만료 처리 (ISSUED → EXPIRED)
     * 만료 스케줄러에서 호출
     */
    public void markAsExpired() {
        if (this.status != VirtualAccountStatus.ISSUED) {
            throw new IllegalStateException(
                    "ISSUED 상태의 가상계좌만 만료 처리 가능합니다. 현재: " + this.status);
        }
        this.status = VirtualAccountStatus.EXPIRED;
        this.expiredAt = LocalDateTime.now();
    }

    public boolean isIssued() {
        return this.status == VirtualAccountStatus.ISSUED;
    }

    public boolean isDeposited() {
        return this.status == VirtualAccountStatus.DEPOSITED;
    }
}
