package com.ainexus.gateway.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理: 统一输出 OpenAI 兼容的 error 报文。
 * {"error": {"message": "...", "type": "...", "code": "..."}}
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Map<String, Object>> handleBiz(BizException e) {
        return build(e.getStatus(), e.getType(), e.getCode(), e.getMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, JsonProcessingException.class})
    public ResponseEntity<Map<String, Object>> handleBadJson(Exception e) {
        return build(400, "invalid_request_error", "invalid_json", "Request body is not valid JSON");
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, Object>> handleIo(IOException e) {
        log.warn("[gateway] relay io error: {}", e.toString());
        return build(502, "upstream_error", "upstream_io", "Upstream relay error: " + e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnknown(Exception e) {
        log.error("[gateway] unhandled error", e);
        return build(500, "gateway_error", "internal_error",
                "Internal gateway error: " + e.getClass().getSimpleName());
    }

    private ResponseEntity<Map<String, Object>> build(int status, String type, String code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", message);
        error.put("type", type);
        error.put("code", code);
        return ResponseEntity.status(HttpStatus.valueOf(status)).body(Map.of("error", error));
    }
}