package com.ainexus.gateway.auth;

import com.ainexus.gateway.common.ErrorResponses;
import com.ainexus.gateway.config.GatewayProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 极简 Admin API 静态口令鉴权，不引入 Spring Security。 */
@Component
@RequiredArgsConstructor
public class AdminAuthInterceptor implements HandlerInterceptor {

    public static final String HEADER = "Admin-Authorization";

    private final GatewayProperties gatewayProperties;
    private final ConsoleSessionService sessionService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            ConsoleSessionService.SessionAccount account = sessionService.verify(authorization.substring(7));
            if (account != null && "ADMIN".equals(account.role())) {
                request.setAttribute(ConsoleAuthInterceptor.ATTRIBUTE, account);
                return true;
            }
            ErrorResponses.write(response, 403, "permission_error", "admin_role_required", "Administrator role is required");
            return false;
        }
        // Backwards-compatible development/script access. Browser UI uses a signed ADMIN session.
        String provided = request.getHeader(HEADER);
        String expected = gatewayProperties.getAdmin().getSecret();
        if (provided == null || expected == null || !MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
            ErrorResponses.write(response, 401, "authentication_error", "invalid_admin_authorization",
                    "Administrator login is required");
            return false;
        }
        return true;
    }
}
