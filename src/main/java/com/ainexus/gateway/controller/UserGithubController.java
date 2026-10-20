package com.ainexus.gateway.controller;

import com.ainexus.gateway.auth.ConsoleAuthInterceptor;
import com.ainexus.gateway.auth.ConsoleSessionService;
import com.ainexus.gateway.common.BizException;
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

@RestController
@RequestMapping("/api/console/github")
@RequiredArgsConstructor
public class UserGithubController {
    private final GithubStarGateService service;
    @GetMapping("/status") public Status status(HttpServletRequest request) { GithubStarGateService.Binding b=service.binding(user(request).id()); GithubStarGateService.Setting s=service.settings(); return new Status(s.enabled(),s.repository(),b.bound(),b.githubLogin(),b.starVerifiedAt(),b.refreshable(),b.accessTokenExpiresAt(),b.refreshTokenExpiresAt()); }
    @GetMapping("/authorize") public Authorize authorize(HttpServletRequest request) { return new Authorize(service.authorizationUrl(user(request).id())); }
    @DeleteMapping("/binding") public void unbind(HttpServletRequest request) { service.unbind(user(request).id()); }
    @GetMapping("/callback") public void callback(@RequestParam String code,@RequestParam String state,HttpServletResponse response) throws IOException { service.bindFromCallback(code,state); response.sendRedirect("/"); }
    private ConsoleSessionService.SessionAccount user(HttpServletRequest request){ConsoleSessionService.SessionAccount a=(ConsoleSessionService.SessionAccount)request.getAttribute(ConsoleAuthInterceptor.ATTRIBUTE);if(a==null||!"USER".equals(a.role()))throw BizException.forbidden("user_role_required","User role is required");return a;}
    public record Status(boolean enabled,String repository,boolean bound,String githubLogin,Object starVerifiedAt,boolean refreshable,Object accessTokenExpiresAt,Object refreshTokenExpiresAt){} public record Authorize(String authorizationUrl){}
}
