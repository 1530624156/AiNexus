package com.ainexus.gateway.admin;

import com.ainexus.gateway.service.GithubStarGateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/github-star")
@RequiredArgsConstructor
public class AdminGithubStarController {
    private final GithubStarGateService service;
    @GetMapping public View get() { GithubStarGateService.Setting s=service.settings(); return new View(s.enabled(),s.repository(),s.publicBaseUrl(),s.clientId(),s.clientSecret()==null?null:"<KEEP_EXISTING>",s.dailyCheckTime()); }
    @PutMapping public View update(@Valid @RequestBody Request request) { GithubStarGateService.Setting s=service.update(new GithubStarGateService.Update(request.enabled(),request.repository(),request.publicBaseUrl(),request.oauthClientId(),request.oauthClientSecret(),request.dailyCheckTime())); return new View(s.enabled(),s.repository(),s.publicBaseUrl(),s.clientId(),s.clientSecret()==null?null:"<KEEP_EXISTING>",s.dailyCheckTime()); }
    public record Request(Boolean enabled,String repository,String publicBaseUrl,String oauthClientId,String oauthClientSecret,String dailyCheckTime){} public record View(boolean enabled,String repository,String publicBaseUrl,String oauthClientId,String oauthClientSecret,String dailyCheckTime){}
}
