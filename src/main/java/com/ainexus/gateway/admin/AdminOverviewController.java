package com.ainexus.gateway.admin;

import com.ainexus.gateway.entity.Channel;
import com.ainexus.gateway.entity.RequestAuditLog;
import com.ainexus.gateway.mapper.ChannelMapper;
import com.ainexus.gateway.mapper.RequestAuditLogMapper;
import com.ainexus.gateway.service.ChannelCircuitBreakerService;
import com.ainexus.gateway.service.ChannelConcurrencyService;
import com.ainexus.gateway.service.ConcurrencyHistoryService;
import com.ainexus.gateway.service.GatewayActivityService;
import com.ainexus.gateway.service.GatewayConcurrencyService;
import com.ainexus.gateway.service.UserGroupConcurrencyService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/** Operations overview: live counters plus selected-window usage and concurrency timeline. */
@RestController
@RequestMapping("/api/admin/overview")
@RequiredArgsConstructor
public class AdminOverviewController {
    private final GatewayConcurrencyService gatewayConcurrencyService;
    private final GatewayActivityService activityService;
    private final UserGroupConcurrencyService userGroupConcurrencyService;
    private final ChannelConcurrencyService channelConcurrencyService;
    private final ChannelCircuitBreakerService circuitBreakerService;
    private final ConcurrencyHistoryService historyService;
    private final ChannelMapper channelMapper;
    private final RequestAuditLogMapper auditMapper;

    @GetMapping
    public Overview response(@RequestParam(defaultValue = "today") String range) {
        LocalDateTime start = start(range);
        GatewayConcurrencyService.Snapshot gateway = gatewayConcurrencyService.snapshot();
        List<RequestAuditLog> audit = auditMapper.selectList(new LambdaQueryWrapper<RequestAuditLog>().ge(RequestAuditLog::getCreateTime, start));
        long tokens = audit.stream().map(RequestAuditLog::getTotalTokens).filter(java.util.Objects::nonNull).mapToLong(Long::longValue).sum();
        long requests = audit.size();
        long totalUsers = audit.stream().map(RequestAuditLog::getAccountId).filter(java.util.Objects::nonNull).distinct().count();
        List<UserGroupConcurrencyService.Snapshot> groups = userGroupConcurrencyService.snapshots();
        List<ChannelSnapshot> channels = channelMapper.selectList(new LambdaQueryWrapper<Channel>().eq(Channel::getStatus, 1).orderByDesc(Channel::getPriority)).stream()
                .map(channel -> new ChannelSnapshot(channel.getId(), channel.getName(), channel.getProvider(), channel.getPriority(), channelConcurrencyService.activeLocal(channel.getId(), channel.getMaxConcurrent() == null ? 0 : channel.getMaxConcurrent()), channel.getMaxConcurrent() == null ? 0 : channel.getMaxConcurrent(), circuitBreakerService.isOpen(toRoute(channel)) ? "CIRCUIT_OPEN" : "HEALTHY")).toList();
        return new Overview(new GatewayStatus(activityService.activeUsers(), activityService.streamingRequests(), gateway.active(), gateway.maxConcurrent(), gateway.waiting(), gateway.queueCapacity()),
                groups.stream().map(group -> new UserGroupStatus(group.name(), group.active(), group.maxConcurrent())).toList(), channels,
                new Usage(tokens, requests, totalUsers), historyService.since(start));
    }

    private com.ainexus.gateway.route.ChannelRoute toRoute(Channel channel) { com.ainexus.gateway.route.ChannelRoute route = new com.ainexus.gateway.route.ChannelRoute(); route.setId(channel.getId()); return route; }
    private LocalDateTime start(String range) { LocalDateTime now = LocalDateTime.now(); return switch (range) { case "24h" -> now.minusHours(24); case "7d" -> now.minusDays(7); case "30d" -> now.minusDays(30); default -> now.toLocalDate().atStartOfDay(); }; }

    public record GatewayStatus(int activeUsers, int streamingRequests, int concurrent, int maxConcurrent, int waiting, int queueCapacity) { }
    public record UserGroupStatus(String name, int active, int limit) { }
    public record ChannelSnapshot(Long id, String name, String provider, int priority, int active, int limit, String health) { }
    public record Usage(long tokens, long requests, long totalActiveUsers) { }
    public record Overview(GatewayStatus gateway, List<UserGroupStatus> userGroups, List<ChannelSnapshot> channels, Usage usage, List<ConcurrencyHistoryService.Point> history) { }
}
