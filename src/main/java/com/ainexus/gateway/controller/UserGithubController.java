package com.ainexus.gateway.controller;

import com.ainexus.gateway.auth.ConsoleAuthInterceptor;
import com.ainexus.gateway.auth.ConsoleSessionService;
import com.ainexus.gateway.common.BizException;
import com.ainexus.gateway.entity.Account;
import com.ainexus.gateway.service.GithubStarGateService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/console/github")
@RequiredArgsConstructor
public class UserGithubController {
    private final GithubStarGateService service;
    private final ConsoleSessionService sessionService;

    @GetMapping("/status")
    public Status status(HttpServletRequest request) {
        GithubStarGateService.Binding binding = service.binding(user(request).id());
        GithubStarGateService.Setting setting = service.settings();
        return new Status(setting.enabled(), setting.repository(), binding.bound(), binding.githubLogin(), binding.starVerifiedAt(),
                binding.refreshable(), binding.accessTokenExpiresAt(), binding.refreshTokenExpiresAt());
    }

    @GetMapping("/authorize")
    public Authorize authorize(HttpServletRequest request) {
        return new Authorize(service.authorizationUrl(user(request).id()));
    }

    /** Public entrypoint for sign-in; only existing GitHub bindings can complete it. */
    @GetMapping("/login-authorize")
    public Authorize loginAuthorize() {
        return new Authorize(service.loginAuthorizationUrl());
    }

    @DeleteMapping("/binding")
    public void unbind(HttpServletRequest request) {
        service.unbind(user(request).id());
    }

    @GetMapping("/callback")
    public void callback(@RequestParam String code, @RequestParam String state, HttpServletResponse response) throws IOException {
        try {
            if (service.isLoginState(state)) {
                Account account = service.loginFromCallback(code, state);
                String fragment = "github_login_token=" + enc(sessionService.issue(account))
                        + "&github_login_username=" + enc(account.getUsername())
                        + "&github_login_role=" + enc(account.getRole());
                response.sendRedirect("/#" + fragment);
                return;
            }
            service.bindFromCallback(code, state);
            response.sendRedirect("/");
        } catch (BizException e) {
            response.sendRedirect("/#github_login_error=" + enc(e.getMessage()));
        }
    }

    private ConsoleSessionService.SessionAccount user(HttpServletRequest request) {
        ConsoleSessionService.SessionAccount account = (ConsoleSessionService.SessionAccount) request.getAttribute(ConsoleAuthInterceptor.ATTRIBUTE);
        if (account == null || !"USER".equals(account.role())) {
            throw BizException.forbidden("user_role_required", "User role is required");
        }
        return account;
    }

    private String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record Status(boolean enabled, String repository, boolean bound, String githubLogin, Object starVerifiedAt,
                         boolean refreshable, Object accessTokenExpiresAt, Object refreshTokenExpiresAt) { }
    public record Authorize(String authorizationUrl) { }
}
