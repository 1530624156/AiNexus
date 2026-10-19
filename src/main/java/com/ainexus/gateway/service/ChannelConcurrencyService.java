package com.ainexus.gateway.service;

import com.ainexus.gateway.redis.RedisSupport;
import com.ainexus.gateway.route.ChannelRoute;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RSemaphore;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;

/**
 * 渠道级并发控制。
 *
 * <p>优先使用 Redis RSemaphore，使 maxConcurrent 在多网关实例间生效；Redis 不可用时退化到本机 Semaphore。
 * 许可证在上游请求（包括 SSE 完整流）结束后释放，因此一个长流会占用一个渠道并发槽位。
 */
@Service
@RequiredArgsConstructor
public class ChannelConcurrencyService {

    private static final String SEMAPHORE_KEY_PREFIX = "ainexus:channel:concurrent:";

    private final RedisSupport redisSupport;
    private final ConcurrentMap<Long, LocalLimiter> localLimiters = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, java.util.concurrent.atomic.AtomicInteger> activeCounters = new ConcurrentHashMap<>();

    /**
     * 尝试占用渠道并发槽位；返回 null 表示当前渠道已满，应尝试下一条容灾渠道。
     */
    public Permit tryAcquire(ChannelRoute route) {
        int limit = route.getMaxConcurrent();
        if (limit <= 0 || route.getId() == null) {
            return Permit.NOOP;
        }
        java.util.concurrent.atomic.AtomicInteger active = activeCounters.computeIfAbsent(route.getId(), ignored -> new java.util.concurrent.atomic.AtomicInteger());

        Boolean redisAcquired = redisSupport.execute(client -> {
            RSemaphore semaphore = client.getSemaphore(SEMAPHORE_KEY_PREFIX + route.getId());
            // Redis 键首次创建时写入配置上限；运行中的额度不重置，避免误放行已有调用。
            semaphore.trySetPermits(limit);
            return semaphore.tryAcquire();
        }, null);
        if (Boolean.TRUE.equals(redisAcquired)) {
            active.incrementAndGet();
            return () -> {
                try { redisSupport.execute(client -> { client.getSemaphore(SEMAPHORE_KEY_PREFIX + route.getId()).release(); return Boolean.TRUE; }, Boolean.FALSE); }
                finally { active.decrementAndGet(); }
            };
        }
        if (Boolean.FALSE.equals(redisAcquired)) {
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

    public int activeLocal(Long channelId, int configuredLimit) {
        if (configuredLimit <= 0 || channelId == null) return 0;
        return activeCounters.getOrDefault(channelId, new java.util.concurrent.atomic.AtomicInteger()).get();
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
