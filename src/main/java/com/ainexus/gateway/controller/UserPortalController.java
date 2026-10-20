package com.ainexus.gateway.controller;

import com.ainexus.gateway.auth.ConsoleAuthInterceptor;
import com.ainexus.gateway.auth.ConsoleSessionService;
import com.ainexus.gateway.entity.RequestAuditLog;
import com.ainexus.gateway.entity.Token;
import com.ainexus.gateway.mapper.RequestAuditLogMapper;
import com.ainexus.gateway.mapper.TokenMapper;
import com.ainexus.gateway.service.GatewayRuntimeSettingsService;
import com.ainexus.gateway.service.ModelAuthorizationService;
import com.ainexus.gateway.service.RouteCacheService;
import com.ainexus.gateway.service.UserQuotaService;
import com.ainexus.gateway.service.GithubStarGateService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** Self-service USER portal: authorized models, own API keys, usage and quota. */
@RestController
@RequestMapping("/api/console")
@RequiredArgsConstructor
public class UserPortalController {
    private final RouteCacheService routeCacheService;
    private final TokenMapper tokenMapper;
    private final RequestAuditLogMapper requestAuditLogMapper;
    private final ModelAuthorizationService modelAuthorizationService;
    private final GatewayRuntimeSettingsService settingsService;
    private final UserQuotaService userQuotaService;
    private final GithubStarGateService githubStarGateService;

    @GetMapping("/models")
    public ModelCatalog models(HttpServletRequest request) {
        ConsoleSessionService.SessionAccount account = account(request);
        List<String> models = "ADMIN".equals(account.role()) ? routeCacheService.getAvailableModels() : routeCacheService.getAvailableModels().stream()
                .filter(modelAuthorizationService.authorizedModels(account.id())::contains).toList();
        return new ModelCatalog(settingsService.get().accessUrl(), models);
    }

    @GetMapping("/api-keys")
    public List<ApiKeyView> keys(HttpServletRequest request) {
        Long accountId = account(request).id();
        return tokenMapper.selectList(new LambdaQueryWrapper<Token>().eq(Token::getAccountId, accountId).orderByDesc(Token::getId))
                .stream().map(token -> new ApiKeyView(token.getId(), token.getAppName(), mask(token.getToken()), token.getStatus(), token.getRemark(), token.getCreateTime())).toList();
    }

    @PostMapping("/api-keys")
    public ApiKeyCreated createKey(HttpServletRequest request, @RequestBody(required = false) Map<String, String> body) {
        ConsoleSessionService.SessionAccount account = account(request);
        if ("USER".equals(account.role())) githubStarGateService.verifyForKeyCreation(account.id());
        Token token = new Token(); token.setAccountId(account.id()); token.setAppName("user-" + account.id() + "-" + randomKey().substring(0, 8));
        token.setToken("sk-ainexus-" + randomKey()); token.setStatus(1); token.setRemark(body == null ? null : body.get("remark"));
        tokenMapper.insert(token); routeCacheService.refresh();
        return new ApiKeyCreated(token.getId(), token.getToken(), token.getRemark());
    }

    @PatchMapping("/api-keys/{id}/status")
    public void updateKeyStatus(HttpServletRequest request, @PathVariable Long id, @RequestBody KeyStatusRequest body) {
        ConsoleSessionService.SessionAccount account = account(request);
        Token token = tokenMapper.selectById(id);
        if (token == null || !account.id().equals(token.getAccountId())) return;
        if (body.status() == 1 && "USER".equals(account.role())) githubStarGateService.verifyForKeyCreation(account.id());
        token.setStatus(body.status() == 1 ? 1 : 0);
        tokenMapper.updateById(token);
        routeCacheService.refresh();
    }

    @DeleteMapping("/api-keys/{id}")
    public void deleteKey(HttpServletRequest request, @PathVariable Long id) {
        Token token = tokenMapper.selectById(id);
        if (token != null && account(request).id().equals(token.getAccountId())) { tokenMapper.deleteById(id); routeCacheService.refresh(); }
    }

    @GetMapping("/usage")
    public UsageView usage(HttpServletRequest request, @RequestParam(required = false) String apiKey, @RequestParam(required = false) String model) {
        Long accountId = account(request).id();
        List<RequestAuditLog> rows = requestAuditLogMapper.selectList(new LambdaQueryWrapper<RequestAuditLog>()
                .eq(RequestAuditLog::getAccountId, accountId)
                .eq(apiKey != null && !apiKey.isBlank(), RequestAuditLog::getAppName, apiKey)
                .eq(model != null && !model.isBlank(), RequestAuditLog::getRequestModel, model));
        long requests = rows.size(); long errors = rows.stream().filter(row -> row.getStatusCode() >= 400).count();
        long input = sum(rows, RequestAuditLog::getInputTokens); long output = sum(rows, RequestAuditLog::getOutputTokens);
        long total = sum(rows, RequestAuditLog::getTotalTokens); long cache = sum(rows, RequestAuditLog::getCacheTokens);
        List<String> apiKeys = tokenMapper.selectList(new LambdaQueryWrapper<Token>().eq(Token::getAccountId, accountId)).stream().map(Token::getAppName).toList();
        List<String> models = requestAuditLogMapper.selectList(new LambdaQueryWrapper<RequestAuditLog>().eq(RequestAuditLog::getAccountId, accountId)).stream().map(RequestAuditLog::getRequestModel).filter(java.util.Objects::nonNull).distinct().sorted().toList();
        return new UsageView(requests, errors, input, output, total, cache, cacheHitRate(input, cache), apiKeys, models, userQuotaService.quotas(accountId));
    }

    private long sum(List<RequestAuditLog> rows, java.util.function.Function<RequestAuditLog, Long> field) { return rows.stream().map(field).filter(java.util.Objects::nonNull).mapToLong(Long::longValue).sum(); }
    private double cacheHitRate(long inputTokens, long cacheTokens) { return inputTokens <= 0 ? 0D : Math.round(cacheTokens * 10_000D / inputTokens) / 100D; }
    private ConsoleSessionService.SessionAccount account(HttpServletRequest request) { return (ConsoleSessionService.SessionAccount) request.getAttribute(ConsoleAuthInterceptor.ATTRIBUTE); }
    private String mask(String value) { return value.length() < 10 ? "********" : value.substring(0, 7) + "****" + value.substring(value.length() - 4); }
    private String randomKey() { byte[] bytes = new byte[24]; new SecureRandom().nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    public record ModelCatalog(String baseUrl, List<String> models) { }
    public record ApiKeyView(Long id, String appName, String tokenMasked, Integer status, String remark, Object createTime) { }
    public record ApiKeyCreated(Long id, String token, String remark) { }
    public record KeyStatusRequest(Integer status) { }
    public record QuotaView(String userGroup, String period, long quota, long used, Long remaining) { }
    public record UsageView(long requests, long errors, long inputTokens, long outputTokens, long totalTokens, long cacheTokens, double cacheHitRate, List<String> apiKeys, List<String> models, List<UserQuotaService.Quota> quotas) { }
}
