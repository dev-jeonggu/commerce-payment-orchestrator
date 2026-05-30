package com.paycore.virtualaccount.domain;

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
 * Payment와 별도로 분리: 가상계좌는 "발급 → 입금 대기 → 입금 확인" 라이프사이클을
 * Payment와 독립적으로 추적해야 함. Payment는 입금 확인 후(DEPOSITED) 생성됨.
 *
 * [낙관적 락] 만료 스케줄러(EXPIRED)와 입금 Webhook(DEPOSITED)의 동시 충돌 방지.
 */
@Entity
@Table(
    name = "virtual_accounts",
    indexes = {
        @Index(name = "idx_va_merchant_order_id", columnList = "merchant_order_id"),
        @Index(name = "idx_va_tx_id", columnList = "tx_id"),
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

    /** 가맹점 주문번호 */
    @Column(name = "merchant_order_id", nullable = false, unique = true)
    private String merchantOrderId;

    /** 내부 트랜잭션 ID (가상계좌 발급 시점에 부여) */
    @Column(name = "tx_id", nullable = false, unique = true)
    private String txId;

    /** 어느 가맹점인지 */
    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "bank_code", nullable = false, length = 10)
    private String bankCode;

    @Column(name = "bank_name", nullable = false, length = 50)
    private String bankName;

    @Column(name = "account_number", nullable = false, length = 30)
    private String accountNumber;

    @Column(name = "holder_name", nullable = false, length = 50)
    private String holderName;

    @Column(name = "amount", nullable = false)
    private Long amount;

    @Column(name = "due_date", nullable = false)
    private LocalDateTime dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VirtualAccountStatus status;

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
    public VirtualAccount(String merchantOrderId, String txId, String merchantId,
                          String bankCode, String bankName, String accountNumber,
                          String holderName, Long amount, LocalDateTime dueDate) {
        this.merchantOrderId = merchantOrderId;
        this.txId = txId;
        this.merchantId = merchantId;
        this.bankCode = bankCode;
        this.bankName = bankName;
        this.accountNumber = accountNumber;
        this.holderName = holderName;
        this.amount = amount;
        this.dueDate = dueDate;
        this.status = VirtualAccountStatus.ISSUED;
    }

    public void markAsDeposited() {
        if (this.status != VirtualAccountStatus.ISSUED) {
            throw new IllegalStateException("ISSUED 상태의 가상계좌만 입금 처리 가능합니다. 현재: " + this.status);
        }
        this.status = VirtualAccountStatus.DEPOSITED;
        this.depositedAt = LocalDateTime.now();
    }

    public void markAsExpired() {
        if (this.status != VirtualAccountStatus.ISSUED) {
            throw new IllegalStateException("ISSUED 상태의 가상계좌만 만료 처리 가능합니다. 현재: " + this.status);
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
