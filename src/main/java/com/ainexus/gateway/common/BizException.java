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

    /**
     * 模型不存在/当前不可路由：采用 OpenAI 语义的 404 model_not_found。
     * 绝不能复用 403 permission_error，否则调用方客户端会把它渲染成"API 密钥无效"。
     */
    public static BizException modelNotFound(String model, String hint) {
        return new BizException(404, "invalid_request_error", "model_not_found",
                "The model '" + model + "' does not exist or is not currently available on this gateway."
                        + hint
                        + " Call GET /v1/models with the same API key to list the models you can use.");
    }

    /** 模型存在，但当前 API Key 所属用户组未被授权：此时才是真正的权限问题(403)。 */
    public static BizException modelNotAuthorized(String model) {
        return new BizException(403, "permission_error", "model_not_authorized",
                "The API key is valid, but the model '" + model + "' is not authorized for it. "
                        + "Model access is granted by user group; call GET /v1/models to list the models this API key can use.");
    }

    /** 模型存在，但当前没有健康的上游渠道(渠道被熔断或全部不可用)。 */
    public static BizException noChannel(String model) {
        return new BizException(503, "service_unavailable", "no_available_channel",
                "The model '" + model + "' is registered but no healthy upstream channel is currently available. Please retry later.");
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