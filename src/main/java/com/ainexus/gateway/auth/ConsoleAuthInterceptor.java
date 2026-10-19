package com.ainexus.gateway.auth;

import com.ainexus.gateway.common.ErrorResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Session auth for the browser console. Admin endpoints additionally require ADMIN role. */
@Component
@RequiredArgsConstructor
public class ConsoleAuthInterceptor implements HandlerInterceptor {
    public static final String HEADER = "Authorization";
    public static final String ATTRIBUTE = "ainexus.console.account";

    private final ConsoleSessionService sessionService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String header = request.getHeader(HEADER);
        if (header == null || !header.startsWith("Bearer ")) {
            ErrorResponses.write(response, 401, "authentication_error", "console_login_required", "Console login is required");
            return false;
        }
        ConsoleSessionService.SessionAccount account = sessionService.verify(header.substring(7));
        if (account == null) {
            ErrorResponses.write(response, 401, "authentication_error", "invalid_console_session", "Console session is invalid or expired");
            return false;
        }
        request.setAttribute(ATTRIBUTE, account);
        return true;
    }
}
