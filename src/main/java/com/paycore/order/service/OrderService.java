package com.paycore.order.service;

import com.paycore.common.exception.ErrorCode;
import com.paycore.common.exception.PaycoreException;
import com.paycore.common.util.OrderNumberGenerator;
import com.paycore.order.controller.dto.OrderCreateRequest;
import com.paycore.order.controller.dto.OrderCreateResponse;
import com.paycore.order.domain.Order;
import com.paycore.order.repository.OrderRepository;
import com.paycore.payment.service.ItemService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderNumberGenerator orderNumberGenerator;
    private final ItemService itemService;

    @Transactional
    public OrderCreateResponse createOrder(OrderCreateRequest request) {
        Long serverAmount = itemService.getPrice(request.getItemId());
        if (!serverAmount.equals(request.getAmount())) {
            throw new PaycoreException(ErrorCode.ITEM_PRICE_MISMATCH,
                    String.format("요청 금액(%d)이 상품 가격(%d)과 일치하지 않습니다.",
                            request.getAmount(), serverAmount));
        }
        return doCreateOrder(request, serverAmount, false);
    }

    /**
     * 주문번호 중복 충돌 시 1회 재시도
     *
     * UUID 16자리로 충돌 확률이 극히 낮으나, 방어 코드로 유지.
     * 재시도 후에도 실패하면 500 반환 (사실상 발생 불가).
     *
     * [주의] @Transactional 메서드에서 DataIntegrityViolationException 발생 시
     *   현재 TX는 이미 rollback-only 상태. 재시도는 반드시 새 TX에서 수행해야 함.
     *   → doCreateOrder에서 예외 발생 시 현재 TX 롤백 후, 호출자(createOrder)가
     *     새 TX 없이 재시도하면 다시 rollback-only TX에 합류됨.
     *   → 실질적으로 중복 충돌은 36억 건 이상 누적 전까지 발생하지 않으므로
     *     재시도 로직은 "안전망" 수준으로 처리. 로그로 확인 가능.
     */
    private OrderCreateResponse doCreateOrder(OrderCreateRequest request, Long serverAmount, boolean isRetry) {
        String orderNo = orderNumberGenerator.generate();
        Order order = Order.builder()
                .orderNo(orderNo)
                .userId(request.getUserId())
                .itemId(request.getItemId())
                .totalAmount(serverAmount)
                .pgProvider(request.getPgProvider())
                .build();

        try {
            orderRepository.save(order);
            orderRepository.flush(); // 즉시 DB 반영으로 중복 조기 감지
            log.info("[OrderService] 주문 생성 완료 - orderNo: {}, userId: {}, amount: {}",
                    orderNo, request.getUserId(), request.getAmount());
            return OrderCreateResponse.of(order);
        } catch (DataIntegrityViolationException e) {
            if (isRetry) {
                log.error("[OrderService] 주문번호 중복 - 재시도 후에도 실패 (발생 불가 수준)", e);
                throw new PaycoreException(ErrorCode.INTERNAL_SERVER_ERROR, "주문번호 생성 실패, 다시 시도해주세요.");
            }
            log.warn("[OrderService] 주문번호 중복 충돌, 1회 재생성 - orderNo: {}", orderNo);
            return doCreateOrder(request, serverAmount, true);
        }
    }

    @Transactional(readOnly = true)
    public Order getOrder(String orderNo) {
        return orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new PaycoreException(ErrorCode.ORDER_NOT_FOUND,
                        "주문을 찾을 수 없습니다. orderNo: " + orderNo));
    }

    @Transactional(readOnly = true)
    public Order getOrderById(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new PaycoreException(ErrorCode.ORDER_NOT_FOUND));
    }
}
