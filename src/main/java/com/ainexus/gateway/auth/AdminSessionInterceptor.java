package com.ainexus.gateway.auth;

import com.ainexus.gateway.common.ErrorResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** ADMIN role gate after console session authentication. */
@Component
@RequiredArgsConstructor
public class AdminSessionInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        Object value = request.getAttribute(ConsoleAuthInterceptor.ATTRIBUTE);
        if (value instanceof ConsoleSessionService.SessionAccount account && "ADMIN".equals(account.role())) return true;
        ErrorResponses.write(response, 403, "permission_error", "admin_role_required", "Administrator role is required");
        return false;
    }
}
