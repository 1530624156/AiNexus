package com.ainexus.gateway.admin;

import com.ainexus.gateway.common.BizException;
import com.ainexus.gateway.entity.Token;
import com.ainexus.gateway.mapper.TokenMapper;
import com.ainexus.gateway.service.RouteCacheService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** 内部应用 Token 管理。Token 原文只在创建接口响应中返回一次。 */
@RestController
@RequestMapping("/api/admin/tokens")
@RequiredArgsConstructor
public class AdminTokenController {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final TokenMapper tokenMapper;
    private final RouteCacheService routeCacheService;

    @GetMapping
    public List<TokenView> list() {
        return tokenMapper.selectList(new LambdaQueryWrapper<Token>().orderByDesc(Token::getId))
                .stream().map(token -> toView(token, null)).toList();
    }

    @PostMapping
    public TokenView create(@Valid @RequestBody TokenUpsertRequest request) {
        Token token = new Token();
        token.setToken(StringUtils.hasText(request.token()) ? request.token().trim() : generateToken());
        token.setAppName(request.appName());
        token.setRateLimitConfig(request.rateLimitConfig());
        token.setStatus(request.status() == null ? 1 : request.status());
        token.setRemark(request.remark());
        tokenMapper.insert(token);
        routeCacheService.refresh();
        return toView(token, token.getToken());
    }

    @PutMapping("/{id}")
    public TokenView update(@PathVariable Long id, @Valid @RequestBody TokenUpsertRequest request) {
        Token existing = requiredToken(id);
        Token update = new Token();
        update.setId(id);
        update.setToken(StringUtils.hasText(request.token()) ? request.token().trim() : existing.getToken());
        update.setAppName(request.appName());
        update.setRateLimitConfig(request.rateLimitConfig());
        update.setStatus(request.status() == null ? existing.getStatus() : request.status());
        update.setRemark(request.remark());
        tokenMapper.updateById(update);
        routeCacheService.refresh();
        return toView(requiredToken(id), null);
    }

    @PatchMapping("/{id}/status")
    public Map<String, Object> changeStatus(@PathVariable Long id, @Valid @RequestBody StatusRequest request) {
        requiredToken(id);
        tokenMapper.update(null, new LambdaUpdateWrapper<Token>().eq(Token::getId, id).set(Token::getStatus, request.status()));
        routeCacheService.refresh();
        return Map.of("id", id, "status", request.status());
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        requiredToken(id);
        tokenMapper.deleteById(id);
        routeCacheService.refresh();
    }

    private Token requiredToken(Long id) {
        Token token = tokenMapper.selectById(id);
        if (token == null) throw BizException.badRequest("token_not_found", "Token not found: " + id);
        return token;
    }

    private String generateToken() {
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        return "sk-ainexus-" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    private TokenView toView(Token token, String revealedToken) {
        return new TokenView(token.getId(), token.getAppName(), mask(token.getToken()), revealedToken, token.getRateLimitConfig(),
                token.getStatus(), token.getRemark(), token.getCreateTime(), token.getUpdateTime());
    }

    private String mask(String raw) {
        return raw == null || raw.length() <= 10 ? "********" : raw.substring(0, 6) + "****" + raw.substring(raw.length() - 4);
    }

    public record TokenUpsertRequest(@NotBlank String appName, String token, String rateLimitConfig, Integer status, String remark) { }
    public record StatusRequest(@NotNull Integer status) { }
    public record TokenView(Long id, String appName, String tokenMasked, String token, String rateLimitConfig,
                            Integer status, String remark, Object createTime, Object updateTime) { }
}
