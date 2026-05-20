package com.paycore.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {

    // Item
    ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "상품을 찾을 수 없습니다."),
    ITEM_PRICE_MISMATCH(HttpStatus.BAD_REQUEST, "요청 금액이 상품 가격과 일치하지 않습니다."),

    // Order
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다."),
    ORDER_ALREADY_PAID(HttpStatus.CONFLICT, "이미 결제된 주문입니다."),
    ORDER_ALREADY_CANCELLED(HttpStatus.CONFLICT, "이미 취소된 주문입니다."),
    INVALID_ORDER_STATUS(HttpStatus.BAD_REQUEST, "유효하지 않은 주문 상태입니다."),

    // Payment
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "결제 정보를 찾을 수 없습니다."),
    PAYMENT_AMOUNT_MISMATCH(HttpStatus.BAD_REQUEST, "결제 금액이 주문 금액과 일치하지 않습니다."),
    PAYMENT_ALREADY_PROCESSED(HttpStatus.CONFLICT, "이미 처리된 결제입니다."),
    PAYMENT_VERIFICATION_FAILED(HttpStatus.BAD_REQUEST, "결제 검증에 실패했습니다."),
    PAYMENT_CANCEL_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "결제 취소에 실패했습니다."),
    CANCEL_AMOUNT_EXCEEDED(HttpStatus.BAD_REQUEST, "취소 요청 금액이 취소 가능 금액을 초과합니다."),

    // Webhook
    WEBHOOK_SIGNATURE_INVALID(HttpStatus.UNAUTHORIZED, "Webhook 서명 검증에 실패했습니다."),

    // PG API
    PG_API_ERROR(HttpStatus.BAD_GATEWAY, "PG사 API 호출 중 오류가 발생했습니다."),
    PG_AUTHENTICATION_FAILED(HttpStatus.UNAUTHORIZED, "PG사 인증에 실패했습니다."),
    PG_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "PG사 서비스를 일시적으로 사용할 수 없습니다."),

    // Billing Key
    BILLING_KEY_NOT_FOUND(HttpStatus.NOT_FOUND, "빌링키를 찾을 수 없습니다."),
    BILLING_KEY_ALREADY_DELETED(HttpStatus.CONFLICT, "이미 삭제된 빌링키입니다."),
    BILLING_CHARGE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "빌링키 결제에 실패했습니다."),

    // Virtual Account
    VIRTUAL_ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "가상계좌 정보를 찾을 수 없습니다."),
    VIRTUAL_ACCOUNT_EXPIRED(HttpStatus.GONE, "입금 기한이 만료된 가상계좌입니다."),

    // Lock
    LOCK_ACQUISITION_FAILED(HttpStatus.CONFLICT, "현재 처리 중인 요청이 있습니다. 잠시 후 다시 시도해주세요."),

    // Common
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");

    private final HttpStatus httpStatus;
    private final String message;

    ErrorCode(HttpStatus httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }
}
