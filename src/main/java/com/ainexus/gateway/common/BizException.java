package com.ainexus.gateway.common;

import lombok.Getter;

/**
 * 业务异常: 携带 OpenAI 兼容的 error 属性(status/type/code), 由 GlobalExceptionHandler 统一渲染。
 */
@Getter
public class BizException extends RuntimeException {

    private final int status;
    private final String type;
    private final String code;

    private BizException(int status, String type, String code, String message) {
        super(message);
        this.status = status;
        this.type = type;
        this.code = code;
    }

    public static BizException badRequest(String code, String message) {
        return new BizException(400, "invalid_request_error", code, message);
    }

    public static BizException unauthorized(String code, String message) {
        return new BizException(401, "authentication_error", code, message);
    }

    public static BizException forbidden(String code, String message) {
        return new BizException(403, "permission_error", code, message);
    }

    public static BizException noChannel(String model) {
        return new BizException(503, "model_not_found", "no_channel",
                "No available channel for model: " + model);
    }

    public static BizException rateLimited(String message) {
        return new BizException(429, "rate_limit_error", "rate_limit_exceeded", message);
    }

    public static BizException tokenQuotaExceeded(String message) {
        return new BizException(429, "rate_limit_error", "token_quota_exceeded", message);
    }

    public static BizException unsupportedProtocolStream(String protocol) {
        return new BizException(400, "invalid_request_error", "stream_not_supported_for_protocol",
                "Streaming is currently supported only for protocol openai-completions; channel protocol is " + protocol);
    }
}