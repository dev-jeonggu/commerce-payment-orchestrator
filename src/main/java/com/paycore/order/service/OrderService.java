package com.paycore.order.service;

import com.paycore.common.exception.ErrorCode;
import com.paycore.common.exception.PaycoreException;
import com.paycore.common.util.OrderNumberGenerator;
import com.paycore.order.controller.dto.OrderCreateRequest;
import com.paycore.order.controller.dto.OrderCreateResponse;
import com.paycore.order.domain.Order;
import com.paycore.order.domain.OrderStatus;
import com.paycore.order.repository.OrderRepository;
import com.paycore.payment.service.ItemService;
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
    private final ItemService itemService;

    @Transactional
    public OrderCreateResponse createOrder(OrderCreateRequest request) {
        String orderNo = orderNumberGenerator.generate();

        Long serverAmount = itemService.getPrice(request.getItemId());
        if (!serverAmount.equals(request.getAmount())) {
            throw new PaycoreException(ErrorCode.ITEM_PRICE_MISMATCH,
                    String.format("요청 금액(%d)이 상품 가격(%d)과 일치하지 않습니다.",
                            request.getAmount(), serverAmount));
        }

        Order order = Order.builder()
                .orderNo(orderNo)
                .userId(request.getUserId())
                .itemId(request.getItemId())
                .totalAmount(serverAmount)
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
