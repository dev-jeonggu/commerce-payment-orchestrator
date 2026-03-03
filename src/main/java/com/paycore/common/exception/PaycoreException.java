package com.paycore.common.exception;

import lombok.Getter;

@Getter
public class PaycoreException extends RuntimeException {

    private final ErrorCode errorCode;

    public PaycoreException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public PaycoreException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
