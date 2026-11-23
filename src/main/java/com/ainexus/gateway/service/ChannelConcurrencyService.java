package com.ainexus.gateway.service;

import com.ainexus.gateway.redis.RedisSupport;
import com.ainexus.gateway.route.ChannelRoute;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RPermitExpirableSemaphore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 渠道级并发控制。
 *
 * <p>优先使用 Redis 可过期许可证，使 maxConcurrent 在多网关实例间生效；Redis 不可用时退化到本机 Semaphore。
 * 许可证在上游请求（包括 SSE 完整流）结束后释放；进程崩溃时会在租约到期后自动归还。
 *
 * <p>两个不可退让的语义（踩过坑，改动前先读 README 的“渠道并发许可”一节）：
 * <ul>
 *   <li>等待时间必须为 0：渠道满时立即返回 null，由 {@code ProxyService} 切换到下一条候选渠道。
 *       让调用方在这里阻塞，等于把一条挂掉的渠道放大成整站无响应。</li>
 *   <li>许可必须带租约：否则进程被杀/SSE 中断时 Redis 里的计数被永久扣减（重启也不会复位），
 *       该渠道会静默失效——所有请求卡在获取许可上，既无响应也无审计记录。</li>
 * </ul>
 */
@Slf4j
@Service
public class ChannelConcurrencyService {

    // v2 deliberately avoids stale permits retained by the former non-expiring RSemaphore.
    private static final String SEMAPHORE_KEY_PREFIX = "ainexus:channel:concurrent:v2:";
    private static final long PERMIT_LEASE_SECONDS = 30 * 60;
    /** Lease must be refreshed well before expiry; supports streams whose lifetime exceeds 30 minutes. */
    private static final long PERMIT_RENEW_INTERVAL_SECONDS = PERMIT_LEASE_SECONDS / 3;
    /** 渠道满载通知的抑制窗口，避免高负载下每次 failover 都重复记日志。 */
    private static final long EXHAUSTED_LOG_INTERVAL_MS = 30_000;

    private final RedisSupport redisSupport;
    private final ScheduledThreadPoolExecutor leaseRenewer;
    private final ConcurrentMap<Long, LocalLimiter> localLimiters = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, java.util.concurrent.atomic.AtomicInteger> activeCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Long> exhaustedLoggedAt = new ConcurrentHashMap<>();

    @Autowired
    public ChannelConcurrencyService(RedisSupport redisSupport) {
        this(redisSupport, new ScheduledThreadPoolExecutor(1, daemonThreadFactory()));
    }

    ChannelConcurrencyService(RedisSupport redisSupport, ScheduledThreadPoolExecutor leaseRenewer) {
        this.redisSupport = redisSupport;
        this.leaseRenewer = leaseRenewer;
        this.leaseRenewer.setRemoveOnCancelPolicy(true);
    }

    /**
     * 尝试占用渠道并发槽位；返回 null 表示当前渠道已满，应尝试下一条容灾渠道。
     */
    public Permit tryAcquire(ChannelRoute route) {
        int limit = route.getMaxConcurrent();
        if (limit <= 0 || route.getId() == null) {
            return Permit.NOOP;
        }
        java.util.concurrent.atomic.AtomicInteger active = activeCounters.computeIfAbsent(route.getId(), ignored -> new java.util.concurrent.atomic.AtomicInteger());

        String permitId = redisSupport.execute(client -> {
            RPermitExpirableSemaphore semaphore = client.getPermitExpirableSemaphore(SEMAPHORE_KEY_PREFIX + route.getId());
            // A new v2 key starts with the configured limit; expired permits self-heal crashed requests.
            semaphore.trySetPermits(limit);
            try {
                // tryAcquire(waitTime, leaseTime, unit)：waitTime=0 → 拿不到就立刻返回 null 走 failover，
                // leaseTime=PERMIT_LEASE_SECONDS → 进程被杀/流中断后许可自动回收。
                // 注意不要退回两参数重载 tryAcquire(lease, unit)——它的第一个参数是等待时间，会把请求挂 30 分钟。
                return semaphore.tryAcquire(0, PERMIT_LEASE_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }, null);
        if (permitId != null) {
            active.incrementAndGet();
            return renewableRedisPermit(route, permitId, active);
        }
        if (redisSupport.available()) {
            logExhausted(route, limit);
            return null;
        }

        LocalLimiter limiter = localLimiters.compute(route.getId(), (id, previous) -> {
            if (previous == null || (previous.limit != limit && previous.semaphore.availablePermits() == previous.limit)) {
                return new LocalLimiter(limit);
            }
            return previous;
        });
        if (!limiter.semaphore.tryAcquire()) {
            return null;
        }
        active.incrementAndGet();
        return () -> { try { limiter.semaphore.release(); } finally { active.decrementAndGet(); } };
    }

    /**
     * Builds a Redis-backed permit that renews its own lease for the full request lifetime.
     * A fixed lease alone is unsafe because streamIdleTimeoutSeconds may be zero: an active SSE
     * can outlive 30 minutes, after which Redis would otherwise hand its slot to another request.
     */
    private Permit renewableRedisPermit(ChannelRoute route, String permitId,
                                        java.util.concurrent.atomic.AtomicInteger active) {
        AtomicBoolean closed = new AtomicBoolean();
        ScheduledFuture<?> renewal = leaseRenewer.scheduleAtFixedRate(() -> {
            if (closed.get()) {
                return;
            }
            Boolean renewed = redisSupport.execute(client -> client
                    .getPermitExpirableSemaphore(SEMAPHORE_KEY_PREFIX + route.getId())
                    .updateLeaseTime(permitId, PERMIT_LEASE_SECONDS, TimeUnit.SECONDS), Boolean.FALSE);
            if (!Boolean.TRUE.equals(renewed)) {
                // Do not cancel the live request: Redis may only be temporarily unavailable. The original
                // lease remains valid until expiry and a later scheduled renewal may recover it.
                // Permit IDs are opaque capability values, so they must never be included in logs.
                log.warn("[relay] failed to renew permit for channel '{}'; lease expires in at most {}s",
                        route.getChannelName(), PERMIT_LEASE_SECONDS);
            }
        }, PERMIT_RENEW_INTERVAL_SECONDS, PERMIT_RENEW_INTERVAL_SECONDS, TimeUnit.SECONDS);
        return () -> {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            renewal.cancel(false);
            try {
                redisSupport.execute(client -> {
                    client.getPermitExpirableSemaphore(SEMAPHORE_KEY_PREFIX + route.getId()).release(permitId);
                    return Boolean.TRUE;
                }, Boolean.FALSE);
            } finally {
                active.decrementAndGet();
            }
        };
    }

    public int activeLocal(Long channelId, int configuredLimit) {
        if (configuredLimit <= 0 || channelId == null) return 0;
        return activeCounters.getOrDefault(channelId, new java.util.concurrent.atomic.AtomicInteger()).get();
    }

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "channel-permit-renewer");
            thread.setDaemon(true);
            return thread;
        };
    }

    @PreDestroy
    void shutdownLeaseRenewer() {
        leaseRenewer.shutdownNow();
    }

    /** 渠道达到配置并发上限的通知（30 秒抑制窗口）；满载是正常的 failover 信号，不是许可泄漏告警。 */
    private void logExhausted(ChannelRoute route, int limit) {
        long now = System.currentTimeMillis();
        Long previous = exhaustedLoggedAt.get(route.getId());
        if (previous != null && now - previous < EXHAUSTED_LOG_INTERVAL_MS) {
            return;
        }
        exhaustedLoggedAt.put(route.getId(), now);
        log.info("[relay] channel '{}' is at maxConcurrent={}, failover to next candidate", route.getChannelName(), limit);
    }

    private record LocalLimiter(int limit, Semaphore semaphore) {
        private LocalLimiter(int limit) {
            this(limit, new Semaphore(limit));
        }
    }

    @FunctionalInterface
    public interface Permit extends AutoCloseable {
        Permit NOOP = () -> { };

        @Override
        void close();
    }
}
