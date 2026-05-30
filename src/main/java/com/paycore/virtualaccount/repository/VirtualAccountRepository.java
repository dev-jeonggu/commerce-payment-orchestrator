package com.paycore.virtualaccount.repository;

import com.paycore.virtualaccount.domain.VirtualAccount;
import com.paycore.virtualaccount.domain.VirtualAccountStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface VirtualAccountRepository extends JpaRepository<VirtualAccount, Long> {

    Optional<VirtualAccount> findByMerchantOrderId(String merchantOrderId);

    Optional<VirtualAccount> findByTxId(String txId);

    /** 만료 스케줄러용: ISSUED 상태이면서 입금 기한이 지난 건 조회 */
    List<VirtualAccount> findByStatusAndDueDateBefore(VirtualAccountStatus status, LocalDateTime threshold);
}
