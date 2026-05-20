package com.paycore.virtualaccount.repository;

import com.paycore.virtualaccount.domain.VirtualAccount;
import com.paycore.virtualaccount.domain.VirtualAccountStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface VirtualAccountRepository extends JpaRepository<VirtualAccount, Long> {

    Optional<VirtualAccount> findByOrderNo(String orderNo);

    Optional<VirtualAccount> findByImpUid(String impUid);

    /**
     * 만료 스케줄러용: ISSUED 상태이면서 입금 기한이 지난 건 조회
     *
     * [성능] idx_va_status_due 인덱스 사용
     * [주의] 대량 만료 발생 시 배치 처리 고려 (Slice/Pageable 변경 가능)
     */
    List<VirtualAccount> findByStatusAndDueDateBefore(VirtualAccountStatus status, LocalDateTime threshold);
}
