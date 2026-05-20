package com.paycore.billing.service;

import com.paycore.billing.controller.dto.BillingKeyChargeRequest;
import com.paycore.billing.controller.dto.BillingKeyChargeResponse;
import com.paycore.billing.controller.dto.BillingKeyRegisterRequest;
import com.paycore.billing.controller.dto.BillingKeyResponse;
import com.paycore.billing.domain.BillingKey;
import com.paycore.billing.repository.BillingKeyRepository;
import com.paycore.common.exception.ErrorCode;
import com.paycore.common.exception.PaycoreException;
import com.paycore.order.domain.Order;
import com.paycore.order.repository.OrderRepository;
import com.paycore.payment.domain.Payment;
import com.paycore.payment.pg.PgBillingCommand;
import com.paycore.payment.pg.PgBillingResult;
import com.paycore.payment.pg.PgRouter;
import com.paycore.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 빌링키 서비스
 *
 * [보안 주의사항]
 * - PG 빌링키는 복호화된 상태로 PG API에만 전달.
 * - 복호화된 키는 절대 Response DTO에 담지 않음.
 * - 로그에 pgBillingKey 출력 금지.
 *
 *
 * [H-2 버그 수정]
 * 기존 charge()는 PG API 호출 후 Payment를 저장하지 않아
 * 결제 이력 추적 불가, 환불 불가, 중복 결제 방지 불가 문제가 있었음.
 * → Order 조회 + 중복 결제 체크 + Payment 저장 + Order.PAID 처리 추가.
 * → 컨트롤러에서 processAfterPayment() 호출로 재고/포인트 후처리 (카드 결제와 동일 패턴).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingKeyService {

    private final BillingKeyRepository billingKeyRepository;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PgRouter pgRouter;

    /**
     * 빌링키 등록
     *
     * [보안] pgBillingKey는 AES256Converter에 의해 DB 저장 시 자동 암호화됨.
     * 서비스 코드에서 별도 암호화 처리 불필요 (JPA 레이어에서 투명하게 처리).
     */
    @Transactional
    public BillingKeyResponse register(BillingKeyRegisterRequest request) {
        // 기본 카드 설정 시 기존 기본 카드 해제
        if (request.isDefault()) {
            clearDefaultBillingKey(request.getUserId(), request.getPgProvider() != null
                    ? request.getPgProvider() : com.paycore.payment.pg.PgProvider.PORTONE);
        }

        BillingKey billingKey = BillingKey.builder()
                .userId(request.getUserId())
                .pgBillingKey(request.getPgBillingKey())
                .maskedCardNo(request.getMaskedCardNo())
                .cardCompany(request.getCardCompany())
                .pgProvider(request.getPgProvider())
                .isDefault(request.isDefault())
                .build();

        billingKeyRepository.save(billingKey);
        log.info("[BillingKeyService] 빌링키 등록 - userId: {}, masked: {}",
                request.getUserId(), request.getMaskedCardNo());
        return BillingKeyResponse.of(billingKey);
    }

    /**
     * 사용자 빌링키 목록 조회
     * pgBillingKey(암호화된 값)는 Response에 포함하지 않음
     */
    @Transactional(readOnly = true)
    public List<BillingKeyResponse> getList(Long userId) {
        return billingKeyRepository
                .findByUserIdAndDeletedFalseOrderByIsDefaultDescCreatedAtDesc(userId)
                .stream()
                .map(BillingKeyResponse::of)
                .collect(Collectors.toList());
    }

    /**
     * 빌링키 결제 (자동결제/정기결제)
     *
     * [보안] getDecryptedPgBillingKey()로 복호화 → PG API에만 전달 → 즉시 GC
     *
     * [선행 조건] request.getOrderId()에 해당하는 Order가 반드시 존재해야 함.
     *   구독 갱신 등 빌링키 결제 전에 Order를 먼저 생성해야 함.
     *   Order가 없으면 결제 이력 추적, 환불이 불가하므로 OrderNotFoundException 발생.
     *
     * [중복 결제 방지]
     * 동일 orderId에 이미 Payment가 존재하면 차단.
     * (구독 서비스의 동일 주기 중복 청구 방지)
     *
     * [트랜잭션 경계]
     * PG API 호출 → Payment 저장 → Order.markAsPaid()가 하나의 TX.
     * PG 성공 + DB 저장 실패 시 Payment 미생성 → TX 롤백. PG 취소는 별도 필요.
     * (카드 결제와 동일한 구조적 한계. impUid UNIQUE로 중복 처리 방지)
     *
     * [후처리] 컨트롤러에서 별도 TX로 processAfterPayment() 호출 (재고 차감 + 포인트 적립).
     */
    @Transactional
    public BillingKeyChargeResponse charge(BillingKeyChargeRequest request) {
        // 1. 주문 조회 및 상태 검증
        Order order = orderRepository.findByOrderNo(request.getOrderId())
                .orElseThrow(() -> new PaycoreException(ErrorCode.ORDER_NOT_FOUND,
                        "빌링키 결제 전 주문을 먼저 생성해주세요. orderNo: " + request.getOrderId()));

        if (order.isPaid()) {
            throw new PaycoreException(ErrorCode.ORDER_ALREADY_PAID,
                    "이미 결제된 주문입니다. orderNo: " + request.getOrderId());
        }

        // 2. 중복 결제 방지: 동일 주문에 이미 Payment가 있으면 차단
        if (paymentRepository.findByMerchantUid(request.getOrderId()).isPresent()) {
            throw new PaycoreException(ErrorCode.PAYMENT_ALREADY_PROCESSED,
                    "해당 주문에 이미 결제 내역이 존재합니다. orderNo: " + request.getOrderId());
        }

        // 3. 빌링키 조회
        BillingKey billingKey = billingKeyRepository
                .findByIdAndUserIdAndDeletedFalse(request.getBillingKeyId(), request.getUserId())
                .orElseThrow(() -> new PaycoreException(ErrorCode.BILLING_KEY_NOT_FOUND));

        // 4. 금액 검증: 클라이언트 금액 vs 서버 주문 금액
        // [버그 수정] request.getAmount()는 클라이언트가 조작 가능한 값.
        // 반드시 서버가 관리하는 order.getTotalAmount()를 PG API 요청과 Payment 저장에 사용.
        // 불일치 시 명시적 에러를 내어 프런트 버그를 조기에 탐지.
        if (!request.getAmount().equals(order.getTotalAmount())) {
            throw new PaycoreException(ErrorCode.PAYMENT_AMOUNT_MISMATCH,
                    String.format("요청 금액(%d)이 주문 금액(%d)과 일치하지 않습니다.",
                            request.getAmount(), order.getTotalAmount()));
        }
        long chargeAmount = order.getTotalAmount();  // 이후 PG 호출 및 Payment 저장에 서버 금액 사용

        PgBillingCommand command = PgBillingCommand.builder()
                .pgBillingKey(billingKey.getDecryptedPgBillingKey())  // 복호화된 키 (로그 출력 금지)
                .orderId(request.getOrderId())
                .amount(chargeAmount)
                .orderName(request.getOrderName())
                .build();

        log.info("[BillingKeyService] 빌링키 결제 시작 - userId: {}, orderId: {}, amount: {}",
                request.getUserId(), request.getOrderId(), chargeAmount);

        PgBillingResult result = pgRouter.route(billingKey.getPgProvider()).chargeBilling(command);

        // [버그 수정] PortOne은 code=0이어도 status가 "failed"일 수 있음.
        // validateResponse()는 code만 체크하므로 status를 별도로 반드시 검증.
        if (!"paid".equals(result.getStatus())) {
            log.warn("[BillingKeyService] 빌링키 결제 PG 상태 이상 - userId: {}, orderId: {}, status: {}",
                    request.getUserId(), request.getOrderId(), result.getStatus());
            throw new PaycoreException(ErrorCode.BILLING_CHARGE_FAILED,
                    "빌링키 결제가 완료되지 않았습니다. PG 상태: " + result.getStatus());
        }

        // 5. Payment 저장 + Order 상태 PAID 처리
        Payment payment = Payment.builder()
                .orderId(order.getId())
                .impUid(result.getPaymentKey())
                .merchantUid(order.getOrderNo())
                .payMethod(result.getPayMethod() != null ? result.getPayMethod() : "billing")
                .paidAmount(chargeAmount)
                .pgProvider(billingKey.getPgProvider())
                .build();
        paymentRepository.save(payment);
        order.markAsPaid();

        log.info("[BillingKeyService] 빌링키 결제 완료 - userId: {}, orderId: {}, impUid: {}",
                request.getUserId(), request.getOrderId(), result.getPaymentKey());

        return BillingKeyChargeResponse.of(payment);
    }

    /**
     * 빌링키 소프트 삭제
     */
    @Transactional
    public void delete(Long billingKeyId, Long userId) {
        BillingKey billingKey = billingKeyRepository
                .findByIdAndUserIdAndDeletedFalse(billingKeyId, userId)
                .orElseThrow(() -> new PaycoreException(ErrorCode.BILLING_KEY_NOT_FOUND));
        billingKey.softDelete();
        log.info("[BillingKeyService] 빌링키 삭제 - id: {}, userId: {}", billingKeyId, userId);
    }

    private void clearDefaultBillingKey(Long userId, com.paycore.payment.pg.PgProvider pgProvider) {
        billingKeyRepository
                .findByUserIdAndPgProviderAndIsDefaultTrueAndDeletedFalse(userId, pgProvider)
                .ifPresent(existing -> existing.setDefault(false));
    }
}
