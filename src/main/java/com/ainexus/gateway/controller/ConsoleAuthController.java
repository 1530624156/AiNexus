package com.ainexus.gateway.controller;

import com.ainexus.gateway.admin.ConsoleAccountService;
import com.ainexus.gateway.auth.ConsoleAuthInterceptor;
import com.ainexus.gateway.auth.ConsoleSessionService;
import com.ainexus.gateway.common.BizException;
import com.ainexus.gateway.entity.Account;
import com.ainexus.gateway.service.GithubStarGateService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class ConsoleAuthController {
    private final ConsoleAccountService accountService;
    private final ConsoleSessionService sessionService;
    private final GithubStarGateService githubStarGateService;

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        Account account = accountService.findByUsername(request.username().trim());
        if (account == null || account.getStatus() == null || account.getStatus() != 1
                || !accountService.matches(request.password(), account.getPasswordHash())) {
            throw BizException.unauthorized("invalid_login", "Invalid username or password");
        }
        return new LoginResponse(sessionService.issue(account), account.getUsername(), account.getRole());
    }

    /**
     * Silently renews a console session when the presented signed token belongs
     * to an account with a still-valid GitHub OAuth refresh authorization.
     */
    @PostMapping("/renew")
    public LoginResponse renew(jakarta.servlet.http.HttpServletRequest request) {
        String authorization = request.getHeader(ConsoleAuthInterceptor.HEADER);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw BizException.unauthorized("console_login_required", "Console login is required");
        }
        ConsoleSessionService.SessionAccount account = sessionService.verifyIgnoringExpiry(authorization.substring(7));
        if (account == null) {
            throw BizException.unauthorized("invalid_console_session", "Console session is invalid");
        }
        Account renewed = githubStarGateService.renewConsoleSession(account.id());
        if (renewed == null) {
            throw BizException.unauthorized("github_session_renewal_unavailable", "GitHub session renewal is unavailable");
        }
        return new LoginResponse(sessionService.issue(renewed), renewed.getUsername(), renewed.getRole());
    }

    @GetMapping("/me")
    public AccountView me(jakarta.servlet.http.HttpServletRequest request) {
        Object value = request.getAttribute(ConsoleAuthInterceptor.ATTRIBUTE);
        if (!(value instanceof ConsoleSessionService.SessionAccount account)) {
            throw BizException.unauthorized("console_login_required", "Console login is required");
        }
        return new AccountView(account.id(), account.username(), account.role());
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) { }
    public record LoginResponse(String token, String username, String role) { }
    public record AccountView(Long id, String username, String role) { }
}
