package com.ainexus.gateway.controller;

import com.ainexus.gateway.admin.ConsoleAccountService;
import com.ainexus.gateway.auth.ConsoleAuthInterceptor;
import com.ainexus.gateway.auth.ConsoleSessionService;
import com.ainexus.gateway.common.BizException;
import com.ainexus.gateway.entity.Account;
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

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        Account account = accountService.findByUsername(request.username().trim());
        if (account == null || account.getStatus() == null || account.getStatus() != 1
                || !accountService.matches(request.password(), account.getPasswordHash())) {
            throw BizException.unauthorized("invalid_login", "Invalid username or password");
        }
        return new LoginResponse(sessionService.issue(account), account.getUsername(), account.getRole());
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
