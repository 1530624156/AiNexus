package com.ainexus.gateway.service;

import com.ainexus.gateway.config.GatewayProperties;
import com.ainexus.gateway.entity.Channel;
import com.ainexus.gateway.entity.ChannelModel;
import com.ainexus.gateway.entity.Token;
import com.ainexus.gateway.mapper.ChannelMapper;
import com.ainexus.gateway.mapper.ChannelModelMapper;
import com.ainexus.gateway.mapper.TokenMapper;
import com.ainexus.gateway.redis.RedisSupport;
import com.ainexus.gateway.route.ChannelRoute;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 路由与内部 Token 的缓存服务。
 *
 * <p>数据库只用于预热和刷新，网关请求链路只查询 Redis；Redis 故障时使用本机快照继续服务。
 * 令牌不会直接作为 Redis Key 的一部分，而是采用 SHA-256 摘要，避免在键空间泄露敏感 Token。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RouteCacheService {

    private static final String CACHE_VERSION_KEY = "ainexus:route:cache-version";
    /** 刷新间隔 60s；保留两个周期，覆盖刷新时的并发读。 */
    private static final long CACHE_TTL_SECONDS = 180;
    private static final String ROUTE_KEY_PREFIX = "ainexus:route:route:";
    private static final String TOKEN_KEY_PREFIX = "ainexus:route:token:";

    private final ChannelMapper channelMapper;
    private final ChannelModelMapper channelModelMapper;
    private final TokenMapper tokenMapper;
    private final GatewayProperties gatewayProperties;
    private final RedisSupport redisSupport;
    private final ObjectMapper objectMapper;

    private volatile Map<String, List<ChannelRoute>> localRoutes = Map.of();
    private volatile Map<String, CachedToken> localTokens = Map.of();

    @PostConstruct
    public void warmUpOnStartup() {
        refresh();
    }

    /** 定期从 MySQL 重建可用 Token/渠道缓存；管理端更新后也应显式调用本方法。 */
    @Scheduled(initialDelay = 30_000, fixedDelay = 60_000)
    public void refresh() {
        try {
            Map<String, List<ChannelRoute>> routes = loadEnabledRoutes();
            Map<String, CachedToken> tokens = loadEnabledTokens();
            localRoutes = immutableRouteMap(routes);
            localTokens = Map.copyOf(tokens);
            writeRedis(routes, tokens);
            log.debug("[route-cache] refreshed: {} models, {} tokens", routes.size(), tokens.size());
        } catch (Exception e) {
            log.warn("[route-cache] refresh failed; retain last snapshot: {}", e.toString());
        }
    }

    /** 返回已预热的公开模型目录，供 OpenAI 兼容 GET /v1/models 使用。 */
    public List<String> getAvailableModels() {
        return localRoutes.keySet().stream().sorted().toList();
    }

    /** 根据公开 model 获取候选渠道。Redis 是主缓存，本机快照仅做 Redis 降级。 */
    public List<ChannelRoute> getAvailableChannels(String model) {
        String json = redisSupport.execute(client -> {
            String version = client.<String>getBucket(CACHE_VERSION_KEY).get();
            return StringUtils.hasText(version) ? client.<String>getBucket(routeKey(version, model)).get() : null;
        }, null);
        if (StringUtils.hasText(json)) {
            try {
                List<ChannelRoute> routes = objectMapper.readValue(json, new TypeReference<List<ChannelRoute>>() { });
                if (!routes.isEmpty()) {
                    return routes;
                }
            } catch (Exception e) {
                log.warn("[route-cache] invalid cached routes for model '{}': {}", model, e.getMessage());
            }
        }
        return localRoutes.getOrDefault(model, List.of());
    }

    /** 根据 Bearer Token 查有效令牌。 */
    public CachedToken getValidToken(String rawToken) {
        if (!StringUtils.hasText(rawToken)) {
            return null;
        }
        String tokenHash = sha256(rawToken);
        String json = redisSupport.execute(client -> {
            String version = client.<String>getBucket(CACHE_VERSION_KEY).get();
            return StringUtils.hasText(version) ? client.<String>getBucket(tokenKey(version, tokenHash)).get() : null;
        }, null);
        if (StringUtils.hasText(json)) {
            try {
                CachedToken token = objectMapper.readValue(json, CachedToken.class);
                return token.status() == 1 ? token : null;
            } catch (Exception e) {
                log.warn("[route-cache] invalid cached token metadata: {}", e.getMessage());
            }
        }
        CachedToken token = localTokens.get(tokenHash);
        return token != null && token.status() == 1 ? token : null;
    }

    private Map<String, List<ChannelRoute>> loadEnabledRoutes() {
        List<Channel> channels = channelMapper.selectList(new LambdaQueryWrapper<Channel>()
                .eq(Channel::getStatus, 1)
                .orderByDesc(Channel::getPriority)
                .orderByAsc(Channel::getId));
        Map<Long, Channel> channelById = new LinkedHashMap<>();
        channels.forEach(channel -> channelById.put(channel.getId(), channel));

        List<ChannelModel> bindings = channelById.isEmpty() ? List.of() : channelModelMapper.selectList(
                new LambdaQueryWrapper<ChannelModel>()
                        .eq(ChannelModel::getStatus, 1)
                        .in(ChannelModel::getChannelId, channelById.keySet())
                        .orderByAsc(ChannelModel::getId));

        Map<String, List<ChannelRoute>> result = new LinkedHashMap<>();
        for (ChannelModel binding : bindings) {
            ChannelRoute route = toRoute(channelById.get(binding.getChannelId()), binding);
            if (route != null) {
                result.computeIfAbsent(route.getModelName(), ignored -> new ArrayList<>()).add(route);
            }
        }
        // DB 数据存在时依然允许静态 mock 渠道作为特定模型的本地联调兜底。
        gatewayProperties.getDegradedChannels().forEach(channel -> {
            ChannelRoute route = new ChannelRoute();
            route.setId(-(long) (channel.toString().hashCode() & 0x7FFF));
            route.setModelName(channel.getModelName());
            route.setUpstreamModelName(channel.getModelName());
            route.setChannelName(channel.getChannelName());
            route.setProtocol(defaultIfBlank(channel.getProtocol(), "openai"));
            route.setBaseUrl(channel.getBaseUrl());
            route.setApiKey(channel.getApiKey());
            route.setWeight(Math.max(1, channel.getWeight()));
            route.setPriority(channel.getPriority());
            result.computeIfAbsent(route.getModelName(), ignored -> new ArrayList<>()).add(route);
        });
        result.values().forEach(routes -> routes.sort(Comparator.comparingInt(ChannelRoute::getPriority).reversed()
                .thenComparing(ChannelRoute::getId, Comparator.nullsLast(Long::compareTo))));
        return result;
    }

    private Map<String, CachedToken> loadEnabledTokens() {
        Map<String, CachedToken> result = new ConcurrentHashMap<>();
        List<Token> dbTokens = tokenMapper.selectList(new LambdaQueryWrapper<Token>().eq(Token::getStatus, 1));
        dbTokens.forEach(token -> result.put(sha256(token.getToken()), new CachedToken(
                token.getId(), token.getAppName(), token.getAccountId(), token.getRateLimitConfig(), token.getStatus())));
        // application.yml keys are development bootstrap tokens. Production should clear this list after Token 管理上线。
        gatewayProperties.getAuth().getInternalKeys().forEach(token -> result.putIfAbsent(sha256(token),
                new CachedToken(null, "bootstrap-config", null, null, 1)));
        return result;
    }

    private void writeRedis(Map<String, List<ChannelRoute>> routes, Map<String, CachedToken> tokens) {
        redisSupport.execute(client -> {
            // 先写完新版本，最后切换版本指针；读请求永远只会看到完整快照。
            String version = Long.toUnsignedString(System.nanoTime(), 36);
            routes.forEach((model, values) -> {
                try {
                    client.<String>getBucket(routeKey(version, model))
                            .set(objectMapper.writeValueAsString(values), CACHE_TTL_SECONDS, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException("serialize route cache failed", e);
                }
            });
            tokens.forEach((hash, token) -> {
                try {
                    client.<String>getBucket(tokenKey(version, hash))
                            .set(objectMapper.writeValueAsString(token), CACHE_TTL_SECONDS, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException("serialize token cache failed", e);
                }
            });
            client.<String>getBucket(CACHE_VERSION_KEY).set(version, CACHE_TTL_SECONDS, TimeUnit.SECONDS);
            return Boolean.TRUE;
        }, Boolean.FALSE);
    }

    /** 将数据库渠道和模型绑定展开为运行时路由，供管理端测试复用相同协议配置。 */
    public ChannelRoute toRoute(Channel channel, ChannelModel binding) {
        if (channel == null || !StringUtils.hasText(binding.getModelName())) {
            return null;
        }
        ChannelRoute route = new ChannelRoute();
        route.setId(channel.getId());
        route.setChannelModelId(binding.getId());
        route.setModelName(binding.getModelName());
        route.setUpstreamModelName(binding.getUpstreamModelName());
        route.setChannelName(channel.getName());
        route.setProvider(channel.getProvider());
        route.setProtocol(defaultIfBlank(channel.getProtocol(), "openai"));
        route.setBaseUrl(channel.getBaseUrl());
        route.setApiKey(channel.getApiKey());
        route.setWeight(channel.getWeight() == null || channel.getWeight() <= 0 ? 1 : channel.getWeight());
        route.setPriority(channel.getPriority() == null ? 0 : channel.getPriority());
        route.setMaxConcurrent(channel.getMaxConcurrent() == null ? 0 : channel.getMaxConcurrent());
        route.setDefaultTestModel(channel.getDefaultTestModel());
        applyProtocolConfig(route, channel.getProtocolConfig());
        return route;
    }

    private void applyProtocolConfig(ChannelRoute route, String config) {
        if (!StringUtils.hasText(config)) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(config);
            setIfText(root, "requestPath", route::setRequestPath);
            setIfText(root, "authHeader", route::setAuthHeader);
            setIfText(root, "authPrefix", route::setAuthPrefix);
            JsonNode headers = root.path("headers");
            if (headers.isObject()) {
                Map<String, String> extraHeaders = new LinkedHashMap<>();
                headers.fields().forEachRemaining(entry -> extraHeaders.put(entry.getKey(), entry.getValue().asText()));
                route.setExtraHeaders(extraHeaders);
            }
        } catch (Exception e) {
            log.warn("[route-cache] invalid protocol_config on channel '{}': {}", route.getChannelName(), e.getMessage());
        }
    }

    private void setIfText(JsonNode root, String field, java.util.function.Consumer<String> consumer) {
        String value = root.path(field).asText();
        if (StringUtils.hasText(value)) {
            consumer.accept(value);
        }
    }

    private String defaultIfBlank(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private Map<String, List<ChannelRoute>> immutableRouteMap(Map<String, List<ChannelRoute>> source) {
        Map<String, List<ChannelRoute>> copy = new LinkedHashMap<>();
        source.forEach((model, routes) -> copy.put(model, List.copyOf(routes)));
        return Map.copyOf(copy);
    }

    private String routeKey(String version, String model) {
        return ROUTE_KEY_PREFIX + version + ":model:"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(model.getBytes(StandardCharsets.UTF_8));
    }

    private String tokenKey(String version, String tokenHash) {
        return TOKEN_KEY_PREFIX + version + ":" + tokenHash;
    }

    private String sha256(String source) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Redis 与本机快照中存放的安全 Token 元数据，绝不回写 Token 原文。 */
    public record CachedToken(Long id, String appName, Long accountId, String rateLimitConfig, int status) { }
}
