package com.ainexus.gateway.service;

import com.ainexus.gateway.common.BizException;
import com.ainexus.gateway.redis.RedisSupport;
import com.ainexus.gateway.route.ChannelRoute;
import com.ainexus.gateway.route.ChannelProtocol;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RAtomicLong;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 请求期路由选择服务。
 *
 * <p>不直接访问 MySQL：候选渠道由 {@link RouteCacheService} 从 Redis 读取，Redis 故障时使用其本机快照。
 * 选择规则为最高 priority 优先，同优先级内按 weight 加权；剩余渠道组成 failover 顺序。
 */
@Service
@RequiredArgsConstructor
public class ChannelService {

    private static final String RR_KEY_PREFIX = "ainexus:rr:";

    private final RouteCacheService routeCacheService;
    private final ChannelCircuitBreakerService channelCircuitBreakerService;
    private final RedisSupport redisSupport;
    private final GatewayRuntimeSettingsService settingsService;
    private final ConcurrentMap<String, AtomicLong> localRoundRobin = new ConcurrentHashMap<>();

    public List<ChannelRoute> route(String model) {
        List<ChannelRoute> matched = routeCacheService.getAvailableChannels(model).stream()
                .filter(route -> !channelCircuitBreakerService.isOpen(route))
                .toList();
        if (matched.isEmpty()) {
            throw BizException.noChannel(model);
        }
        List<ChannelRoute> nativeRoutes = matched.stream()
                .filter(route -> ChannelProtocol.parse(route.getProtocol()) == ChannelProtocol.OPENAI_COMPLETIONS).toList();
        if (!nativeRoutes.isEmpty()) return orderByPriorityAndWeight(model, nativeRoutes);
        if (!settingsService.get().protocolConversionEnabled()) throw BizException.noChannel(model);
        return orderByPriorityAndWeight(model, matched);
    }

    private List<ChannelRoute> orderByPriorityAndWeight(String model, List<ChannelRoute> sortedRoutes) {
        int primaryPriority = sortedRoutes.getFirst().getPriority();
        List<ChannelRoute> primaryGroup = sortedRoutes.stream()
                .filter(route -> route.getPriority() == primaryPriority)
                .toList();
        ChannelRoute selected = selectWeighted(primaryGroup, nextSequence(model + ":p" + primaryPriority));

        List<ChannelRoute> ordered = new ArrayList<>(sortedRoutes.size());
        ordered.add(selected);
        primaryGroup.stream().filter(route -> route != selected).forEach(ordered::add);
        sortedRoutes.stream().filter(route -> route.getPriority() != primaryPriority).forEach(ordered::add);
        return ordered;
    }

    private ChannelRoute selectWeighted(List<ChannelRoute> routes, long sequence) {
        int totalWeight = routes.stream().mapToInt(ChannelRoute::getWeight).map(weight -> Math.max(1, weight)).sum();
        int slot = (int) Math.floorMod(sequence, totalWeight);
        int cursor = 0;
        for (ChannelRoute route : routes) {
            cursor += Math.max(1, route.getWeight());
            if (slot < cursor) {
                return route;
            }
        }
        return routes.getFirst();
    }

    private long nextSequence(String key) {
        Long redisSeq = redisSupport.execute(client -> {
            RAtomicLong counter = client.getAtomicLong(RR_KEY_PREFIX + key);
            return counter.incrementAndGet();
        }, null);
        if (redisSeq != null) {
            return redisSeq;
        }
        return localRoundRobin.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
    }
}
