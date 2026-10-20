package com.ainexus.gateway.controller;

import com.ainexus.gateway.entity.RequestAuditLog;
import com.ainexus.gateway.mapper.RequestAuditLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared console leaderboard aggregated from immutable request audit records. */
@RestController
@RequestMapping("/api/console/usage-ranking")
@RequiredArgsConstructor
public class UsageRankingController {
    private final RequestAuditLogMapper auditMapper;

    @GetMapping
    public UsageRankingResponse ranking() {
        List<RequestAuditLog> rows = auditMapper.selectList(new LambdaQueryWrapper<RequestAuditLog>()
                .isNotNull(RequestAuditLog::getUserName)
                .orderByDesc(RequestAuditLog::getCreateTime));
        Map<String, List<RequestAuditLog>> byUser = new LinkedHashMap<>();
        rows.forEach(row -> {
            if (StringUtils.hasText(row.getUserName())) {
                byUser.computeIfAbsent(row.getUserName(), ignored -> new java.util.ArrayList<>()).add(row);
            }
        });
        List<UsageRank> rankings = byUser.entrySet().stream()
                .map(entry -> toRank(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingLong(UsageRank::totalTokens).reversed()
                        .thenComparing(UsageRank::username))
                .toList();
        List<UsageRank> indexed = java.util.stream.IntStream.range(0, rankings.size())
                .mapToObj(index -> rankings.get(index).withRank(index + 1))
                .toList();
        return new UsageRankingResponse(indexed, indexed.stream().mapToLong(UsageRank::totalTokens).sum(), indexed.size());
    }

    private UsageRank toRank(String username, List<RequestAuditLog> rows) {
        long input = sum(rows, RequestAuditLog::getInputTokens);
        long cache = sum(rows, RequestAuditLog::getCacheTokens);
        long prompt = input + cache;
        double cacheHitRate = prompt == 0 ? 0D : Math.round(cache * 10_000D / prompt) / 100D;
        return new UsageRank(0, username, rows.size(), sum(rows, RequestAuditLog::getTotalTokens), cache, cacheHitRate);
    }

    private long sum(List<RequestAuditLog> rows, java.util.function.Function<RequestAuditLog, Long> field) {
        return rows.stream().map(field).filter(java.util.Objects::nonNull).mapToLong(Long::longValue).sum();
    }

    public record UsageRank(int rank, String username, long requests, long totalTokens, long cacheTokens, double cacheHitRate) {
        private UsageRank withRank(int value) {
            return new UsageRank(value, username, requests, totalTokens, cacheTokens, cacheHitRate);
        }
    }
    public record UsageRankingResponse(List<UsageRank> rankings, long totalTokens, int totalUsers) { }
}
