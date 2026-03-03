package com.paycore.order.controller;

import com.paycore.common.response.ApiResponse;
import com.paycore.order.controller.dto.OrderCreateRequest;
import com.paycore.order.controller.dto.OrderCreateResponse;
import com.paycore.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Order API", description = "주문 관리 API")
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @Operation(
        summary = "주문 생성",
        description = """
            주문을 생성하고 PENDING 상태로 저장합니다.

            **핵심 포인트**
            - orderNo는 서버에서 unique하게 생성
            - 금액은 서버가 신뢰 기준 (실무: 상품 가격 재조회)
            - 이후 PG 결제창에서 merchant_uid로 이 orderNo를 사용
            """
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OrderCreateResponse> createOrder(@Valid @RequestBody OrderCreateRequest request) {
        return ApiResponse.success("주문이 생성되었습니다.", orderService.createOrder(request));
    }
}
