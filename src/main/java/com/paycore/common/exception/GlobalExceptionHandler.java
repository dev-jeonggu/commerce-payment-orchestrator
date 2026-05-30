package com.paycore.common.exception;

import com.paycore.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(PaycoreException.class)
    public ResponseEntity<ApiResponse<Void>> handlePaycoreException(PaycoreException e) {
        log.warn("[PaycoreException] {} - {}", e.getErrorCode(), e.getMessage());
        return ResponseEntity
                .status(e.getErrorCode().getHttpStatus())
                .body(ApiResponse.error(e.getMessage()));
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<Void>> handleBindException(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .findFirst()
                .orElse("입력값 검증 오류");
        log.warn("[BindException] {}", message);
        return ResponseEntity.badRequest().body(ApiResponse.error(message));
    }

    /**
     * 비즈니스 상태 위반 예외 처리 (이중 취소, 만료 VA 입금 등).
     * Payment.cancel(), VirtualAccount.markAsDeposited() 등에서 발생.
     * 400이 아닌 409(Conflict)로 내려야 가맹점 측에서 재시도 없이 상태 오류로 인식함.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalStateException(IllegalStateException e) {
        log.warn("[IllegalStateException] {}", e.getMessage());
        return ResponseEntity
                .status(org.springframework.http.HttpStatus.CONFLICT)
                .body(ApiResponse.error(e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        log.error("[UnhandledException]", e);
        return ResponseEntity
                .internalServerError()
                .body(ApiResponse.error(ErrorCode.INTERNAL_SERVER_ERROR.getMessage()));
    }
}
