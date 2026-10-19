package com.ainexus.gateway.auth;

import com.ainexus.gateway.common.BizException;
import com.ainexus.gateway.common.ErrorResponses;
import com.ainexus.gateway.config.GatewayProperties;
import com.ainexus.gateway.service.InternalAuthService;
import com.ainexus.gateway.service.AuditLogService;
import com.ainexus.gateway.service.RateLimitService;
import com.ainexus.gateway.service.RouteCacheService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 内部鉴权拦截器: 校验 Header 中的 {@code Authorization: Bearer {Internal-Key}}。
 * 无权限直接 401 阻断, 不进入转发链路。
 */
@Component
@RequiredArgsConstructor
public class InternalAuthInterceptor implements HandlerInterceptor {

    private final InternalAuthService internalAuthService;
    private final RateLimitService rateLimitService;
    private final AuditLogService auditLogService;
    private final GatewayProperties gatewayProperties;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!gatewayProperties.getAuth().isEnabled()) {
            return true; // 显式关闭鉴权(仅建议本机调试)
        }
        String key = extractBearerKey(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (key == null) {
            ErrorResponses.write(response, 401, "authentication_error", "missing_token",
                    "Missing Authorization: Bearer {Internal-Key} header");
            auditLogService.submit(new AuditLogService.AuditEvent(java.util.UUID.randomUUID().toString(), null, null, null, null, null, null, null, null, null, "unknown", false, null, null, null, null, null, null, null, null, 401, null, 0, null, 0L, null, "missing_token", "missing_token"));
            return false;
        }
        RouteCacheService.CachedToken token = internalAuthService.find(key);
        if (token == null) {
            ErrorResponses.write(response, 401, "authentication_error", "invalid_token",
                    "Invalid internal API key");
            auditLogService.submit(new AuditLogService.AuditEvent(java.util.UUID.randomUUID().toString(), null, null, null, null, null, null, null, null, null, "unknown", false, null, null, null, null, null, null, null, null, 401, null, 0, null, 0L, null, "invalid_token", "invalid_token"));
            return false;
        }
        request.setAttribute("ainexus.auth.appName", token.appName());
        request.setAttribute("ainexus.auth.accountId", token.accountId());
        // 令牌校验成功后按 app_name 执行分布式动态限流。
        try {
            rateLimitService.check(token);
        } catch (BizException e) {
            auditLogService.submit(new AuditLogService.AuditEvent(java.util.UUID.randomUUID().toString(), token.appName(), token.appName(), token.accountId(), null, null, null, null, null, null, "unknown", false, null, null, null, null, null, null, null, null, e.getStatus(), null, 0, null, 0L, null, e.getCode(), e.getMessage()));
            throw e;
        }
        return true;
    }

    static String extractBearerKey(String authorization) {
        if (authorization == null) {
            return null;
        }
        String trimmed = authorization.trim();
        if (trimmed.toLowerCase().startsWith("bearer ")) {
            return trimmed.substring(7).trim();
        }
        return trimmed.isEmpty() ? null : trimmed;
    }
}