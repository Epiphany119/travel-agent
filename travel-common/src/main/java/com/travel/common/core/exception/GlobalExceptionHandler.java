package com.travel.common.core.exception;

import com.travel.common.core.result.ApiResult;
import com.travel.common.exception.RateLimitException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理
 *
 * <p>统一处理业务异常、限流异常、参数校验异常和系统异常</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResult<Void>> handleBusinessException(BusinessException ex) {
        log.warn("业务异常: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(ApiResult.error(ex.getCode(), ex.getMessage()));
    }

    /** 限流异常 */
    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<ApiResult<Void>> handleRateLimitException(RateLimitException ex) {
        log.warn("限流异常: service={}, count={}/{}, reset={}",
                ex.getServiceName(), ex.getCurrentCount(), ex.getLimit(), ex.getResetHint());
        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ApiResult.error(429, ex.getMessage()));
    }

    /** @Valid 参数校验异常 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResult<Void>> handleValidationException(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("参数校验失败");
        log.warn("参数校验失败: {}", message);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(ApiResult.error(400, message));
    }

    /** 约束违反异常（@Valid 路径变量等） */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResult<Void>> handleConstraintViolationException(ConstraintViolationException ex) {
        log.warn("约束违反: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(ApiResult.error(400, ex.getMessage()));
    }

    /** 系统异常（兜底） */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> handleException(Exception ex) {
        log.error("系统异常", ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResult.error(500, "系统暂时不可用，请稍后重试"));
    }
}
