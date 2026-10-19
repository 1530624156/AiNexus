package com.ainexus.gateway.service;

import com.ainexus.gateway.common.BizException;
import com.ainexus.gateway.redis.RedisSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 内部应用动态限流。
 *
 * <p>从 gateway_token.rate_limit_config 读取 {"permitsPerSecond":5}。
 * app_name 是默认限流粒度；未提供 app_name 时退化为 Token ID。Redis 不可用时保持网关可用而跳过分布式限流。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private static final String KEY_PREFIX = "ainexus:rate-limit:";

    private final RedisSupport redisSupport;
    private final ObjectMapper objectMapper;

    public void check(RouteCacheService.CachedToken token) {
        RateLimitConfig config = parse(token.rateLimitConfig());
        if (config == null) {
            return;
        }
        String scope = StringUtils.hasText(token.appName()) ? token.appName() : "token-" + token.id();
        String key = KEY_PREFIX + sha256(scope) + ":" + config.fingerprint();
        Boolean allowed = redisSupport.execute(client -> {
            RRateLimiter limiter = client.getRateLimiter(key);
            limiter.trySetRate(RateType.OVERALL, config.permitsPerSecond(), 1, RateIntervalUnit.SECONDS);
            return limiter.tryAcquire();
        }, Boolean.TRUE);
        if (!Boolean.TRUE.equals(allowed)) {
            throw BizException.rateLimited("Rate limit exceeded for application: " + scope);
        }
    }

    private RateLimitConfig parse(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            int permits = root.path("permitsPerSecond").asInt(0);
            if (permits <= 0) {
                return null;
            }
            int burst = root.path("burst").asInt(permits);
            return new RateLimitConfig(permits, Math.max(permits, burst));
        } catch (Exception e) {
            log.warn("[rate-limit] ignore invalid rate_limit_config for app '{}': {}", raw, e.getMessage());
            return null;
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                result.append(String.format("%02x", b));
            }
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record RateLimitConfig(int permitsPerSecond, int burst) {
        private String fingerprint() {
            return permitsPerSecond + "-" + burst;
        }
    }
}
