package com.ainexus.gateway.service;

import com.ainexus.gateway.entity.Channel;
import com.ainexus.gateway.mapper.ChannelMapper;
import com.ainexus.gateway.redis.RedisSupport;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ainexus.gateway.route.ChannelRoute;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 渠道连续失败熔断。
 *
 * <p>可重试上游错误（连接/Socket 超时、HTTP 429、HTTP 5xx）累计到 Redis。连续失败达到阈值后打开
 * 5 分钟熔断；选路时跳过熔断渠道。MySQL failure_count 是异步持久化观测值，Redis 是请求期权威状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelCircuitBreakerService {

    private static final int FAILURE_THRESHOLD = 5;
    private static final long COUNTER_TTL_SECONDS = 600;
    private static final long CIRCUIT_TTL_SECONDS = 300;
    private static final String FAILURE_KEY_PREFIX = "ainexus:circuit:failures:";
    private static final String BREAK_KEY_PREFIX = "ainexus:circuit:open:";

    private final RedisSupport redisSupport;
    private final ChannelMapper channelMapper;
    private final VirtualThreadTaskExecutor virtualThreadTaskExecutor;

    public boolean isOpen(ChannelRoute route) {
        if (route.getId() == null || route.getId() <= 0) {
            return false;
        }
        return Boolean.TRUE.equals(redisSupport.execute(client ->
                client.getBucket(BREAK_KEY_PREFIX + route.getId()).isExists(), Boolean.FALSE));
    }

    public void recordFailure(ChannelRoute route, String reason) {
        if (route.getId() == null || route.getId() <= 0) {
            return;
        }
        int failures = redisSupport.execute(client -> {
            RAtomicLong counter = client.getAtomicLong(FAILURE_KEY_PREFIX + route.getId());
            long value = counter.incrementAndGet();
            if (value == 1) {
                counter.expire(COUNTER_TTL_SECONDS, TimeUnit.SECONDS);
            }
            if (value >= FAILURE_THRESHOLD) {
                client.<String>getBucket(BREAK_KEY_PREFIX + route.getId())
                        .set("true", CIRCUIT_TTL_SECONDS, TimeUnit.SECONDS);
            }
            return (int) Math.min(value, Integer.MAX_VALUE);
        }, 0);
        if (failures > 0) {
            persistFailureCount(route.getId(), failures);
        } else {
            incrementFailureCount(route.getId());
        }
        if (failures >= FAILURE_THRESHOLD) {
            log.warn("[circuit] opened channel '{}' for {}s after {} consecutive failures: {}",
                    route.getChannelName(), CIRCUIT_TTL_SECONDS, failures, reason);
        }
    }

    /** 成功的完整响应才算恢复，SSE 中途断开不调用本方法。 */
    public void recordSuccess(ChannelRoute route) {
        if (route.getId() == null || route.getId() <= 0) {
            return;
        }
        redisSupport.execute(client -> {
            client.getAtomicLong(FAILURE_KEY_PREFIX + route.getId()).delete();
            return Boolean.TRUE;
        }, Boolean.FALSE);
        persistFailureCount(route.getId(), 0);
    }

    private void persistFailureCount(Long channelId, int count) {
        virtualThreadTaskExecutor.submit(() -> {
            try {
                Channel update = new Channel();
                update.setId(channelId);
                update.setFailureCount(count);
                channelMapper.updateById(update);
            } catch (Exception e) {
                log.warn("[circuit] async failure_count update failed for channel {}: {}", channelId, e.toString());
            }
        });
    }

    private void incrementFailureCount(Long channelId) {
        virtualThreadTaskExecutor.submit(() -> {
            try {
                channelMapper.update(null, new LambdaUpdateWrapper<Channel>()
                        .eq(Channel::getId, channelId)
                        .setSql("failure_count = failure_count + 1"));
            } catch (Exception e) {
                log.warn("[circuit] async failure_count increment failed for channel {}: {}", channelId, e.toString());
            }
        });
    }
}
