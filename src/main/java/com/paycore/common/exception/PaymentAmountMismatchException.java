package com.paycore.common.exception;

public class PaymentAmountMismatchException extends PaycoreException {

    public PaymentAmountMismatchException() {
        super(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
    }

    public PaymentAmountMismatchException(long orderAmount, long paidAmount) {
        super(ErrorCode.PAYMENT_AMOUNT_MISMATCH,
                String.format("주문 금액(%d원)과 결제 금액(%d원)이 일치하지 않습니다.", orderAmount, paidAmount));
    }
}
