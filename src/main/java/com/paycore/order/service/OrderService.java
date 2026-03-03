package com.paycore.order.service;

import com.paycore.common.exception.ErrorCode;
import com.paycore.common.exception.PaycoreException;
import com.paycore.common.util.OrderNumberGenerator;
import com.paycore.order.controller.dto.OrderCreateRequest;
import com.paycore.order.controller.dto.OrderCreateResponse;
import com.paycore.order.domain.Order;
import com.paycore.order.domain.OrderStatus;
import com.paycore.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderNumberGenerator orderNumberGenerator;

    /**
     * 주문 생성 (사전 검증)
     * 금액은 서버가 신뢰 기준 - 클라이언트가 보낸 금액을 그대로 쓰지 않고
     * 실무에서는 itemId로 상품 가격을 재조회하여 검증
     */
    @Transactional
    public OrderCreateResponse createOrder(OrderCreateRequest request) {
        String orderNo = orderNumberGenerator.generate();

        // 실무에서는 itemId로 상품 가격 재조회 및 재고 확인
        // Long serverAmount = itemService.getPrice(request.getItemId());
        // 여기서는 request.getAmount()를 신뢰 기준으로 사용 (데모)

        Order order = Order.builder()
                .orderNo(orderNo)
                .userId(request.getUserId())
                .itemId(request.getItemId())
                .totalAmount(request.getAmount())
                .build();

        orderRepository.save(order);
        log.info("[OrderService] 주문 생성 완료 - orderNo: {}, userId: {}, amount: {}",
                orderNo, request.getUserId(), request.getAmount());

        return OrderCreateResponse.of(order);
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
