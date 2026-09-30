package com.cullpilot.backend.api;

import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApiException(ApiException exception) {
        return response(exception.getStatus(), exception.getCode(), exception.getMessage(),
                exception.getDetails());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError error : exception.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return response(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR",
                "请求参数校验失败", Map.of("fields", fields));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(ConstraintViolationException exception) {
        return response(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR",
                "请求参数校验失败", exception.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableMessage(HttpMessageNotReadableException exception) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST_BODY",
                "请求体格式不正确", null);
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ApiErrorResponse> handleMissingRequestPart(Exception exception) {
        return response(HttpStatus.BAD_REQUEST, "MISSING_REQUEST_PART",
                "请求缺少必要的表单字段，请检查 multipart 字段名和请求类型", null);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleMaxUploadSize(MaxUploadSizeExceededException exception) {
        return response(HttpStatus.PAYLOAD_TOO_LARGE, "REQUEST_TOO_LARGE",
                "上传请求超过服务器允许的大小", null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrity(DataIntegrityViolationException exception) {
        return response(HttpStatus.CONFLICT, "DATA_INTEGRITY_ERROR",
                "数据保存失败", null);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorResponse> handleOptimisticLocking(
            ObjectOptimisticLockingFailureException exception) {
        return response(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                "资源已经被其他操作更新，请刷新后重试", null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception) {
        log.error("Unhandled API exception", exception);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "服务器内部错误", null);
    }

    private ResponseEntity<ApiErrorResponse> response(
            HttpStatus status,
            String code,
            String message,
            Object details) {
        ApiErrorResponse body = new ApiErrorResponse(
                new ApiError(code, message, details),
                new ApiMeta(UUID.randomUUID().toString()));
        return ResponseEntity.status(status).body(body);
    }
}
