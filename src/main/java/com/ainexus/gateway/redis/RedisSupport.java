package com.ainexus.gateway.redis;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisConnectionException;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * Redisson 降级式连接管理器。
 *
 * <p>设计要点(网关高可用第一原则——Redis 挂了绝不能拖垮网关):
 * <ul>
 *   <li>启动时尝试连接, 失败仅告警, 网关以"本地降级模式"继续运行(鉴权走配置白名单、轮询走本地计数器、缓存走本地)</li>
 *   <li>后台定时任务周期性重连, Redis 恢复后自动接管</li>
 *   <li>所有操作经 {@link #execute(Function, Object)} 包裹: Redis 不可用/异常时返回降级值, 绝不向上抛异常影响主链路</li>
 * </ul>
 *
 * <p>注意: 这里刻意不使用 redisson-spring-boot-starter —— 该 starter 会在启动期强制连接 Redis,
 * Redis 不可用时直接导致应用启动失败, 违背网关的降级容错目标。
 */
@Slf4j
@Component
public class RedisSupport {

    private final String host;
    private final int port;
    private final String password;
    private final int database;

    private volatile RedissonClient client;

    public RedisSupport(@Value("${spring.data.redis.host:localhost}") String host,
                        @Value("${spring.data.redis.port:6379}") int port,
                        @Value("${spring.data.redis.password:}") String password,
                        @Value("${spring.data.redis.database:0}") int database) {
        this.host = host;
        this.port = port;
        this.password = password;
        this.database = database;
    }

    @PostConstruct
    public synchronized void connect() {
        if (client != null) {
            return;
        }
        try {
            Config config = new Config();
            String address = "redis://" + host + ":" + port;
            config.useSingleServer()
                    .setAddress(address)
                    .setDatabase(database)
                    .setPassword(password == null || password.isBlank() ? null : password)
                    .setConnectTimeout(3000)
                    .setConnectionPoolSize(16)
                    .setConnectionMinimumIdleSize(4);
            client = Redisson.create(config);
            log.info("[redis] Redisson connected: {}", address);
        } catch (Exception e) {
            client = null;
            log.warn("[redis] Redis unavailable ({}), gateway degraded mode: static auth whitelist / local counter / no rate-limit", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        RedissonClient c = client;
        if (c != null) {
            try {
                c.shutdown();
            } catch (Exception ignored) {
                // 关闭阶段异常忽略
            }
        }
    }

    /** 后台重连: Redis 恢复后自动由降级模式切回 */
    @Scheduled(initialDelay = 10_000, fixedDelay = 15_000)
    public void reconnectIfNeeded() {
        if (client == null) {
            connect();
        }
    }

    public boolean available() {
        return client != null;
    }

    /**
     * 在 Redis 上执行一个操作。Redis 不可用或执行异常时返回 {@code fallback}, 绝不向外抛异常。
     */
    public <T> T execute(Function<RedissonClient, T> action, T fallback) {
        RedissonClient c = client;
        if (c == null) {
            return fallback;
        }
        try {
            return action.apply(c);
        } catch (RedisConnectionException e) {
            log.warn("[redis] connection error ({}), mark degraded, scheduled reconnect will restore", e.getMessage());
            client = null;
            return fallback;
        } catch (Exception e) {
            log.debug("[redis] op failed: {}", e.toString());
            return fallback;
        }
    }
}