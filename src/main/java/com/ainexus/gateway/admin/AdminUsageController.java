package com.ainexus.gateway.admin;

import com.ainexus.gateway.entity.RequestAuditLog;
import com.ainexus.gateway.mapper.RequestAuditLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Administrative usage analytics based on immutable request audit snapshots. */
@RestController
@RequestMapping("/api/admin/usage")
@RequiredArgsConstructor
public class AdminUsageController {
    private final RequestAuditLogMapper auditMapper;

    @GetMapping
    public UsageResponse usage(@RequestParam(required = false) String user,
                               @RequestParam(required = false) String provider,
                               @RequestParam(required = false) String apiType) {
        List<RequestAuditLog> rows = auditMapper.selectList(new LambdaQueryWrapper<RequestAuditLog>()
                .eq(StringUtils.hasText(user), RequestAuditLog::getUserName, user)
                .eq(StringUtils.hasText(provider), RequestAuditLog::getProvider, provider)
                .eq(StringUtils.hasText(apiType), RequestAuditLog::getApiType, apiType)
                .orderByDesc(RequestAuditLog::getCreateTime));
        Totals totals = totals(rows);
        return new UsageResponse(totals, distribution(rows, RequestAuditLog::getProvider), distribution(rows, RequestAuditLog::getUpstreamAccount),
                distribution(rows, RequestAuditLog::getModelGroupNames), distribution(rows, RequestAuditLog::getRequestModel),
                distribution(rows, RequestAuditLog::getUserGroupNames), distribution(rows, RequestAuditLog::getUserName));
    }

    @GetMapping("/filters")
    public FilterOptions filters() {
        List<RequestAuditLog> rows = auditMapper.selectList(new LambdaQueryWrapper<>());
        return new FilterOptions(options(rows, RequestAuditLog::getUserName), options(rows, RequestAuditLog::getProvider), options(rows, RequestAuditLog::getApiType));
    }

    private List<String> options(List<RequestAuditLog> rows, java.util.function.Function<RequestAuditLog, String> field) {
        return rows.stream().map(field).filter(StringUtils::hasText).distinct().sorted().toList();
    }
    private Totals totals(List<RequestAuditLog> rows) {
        long input = sum(rows, RequestAuditLog::getInputTokens);
        long cache = sum(rows, RequestAuditLog::getCacheTokens);
        return new Totals(rows.size(), input, sum(rows, RequestAuditLog::getOutputTokens), sum(rows, RequestAuditLog::getTotalTokens), cache, cacheHitRate(input, cache));
    }
    private long sum(List<RequestAuditLog> rows, java.util.function.Function<RequestAuditLog, Long> value) { return rows.stream().map(value).filter(java.util.Objects::nonNull).mapToLong(Long::longValue).sum(); }
    private double cacheHitRate(long inputTokens, long cacheTokens) { long totalPromptTokens = inputTokens + cacheTokens; return totalPromptTokens <= 0 ? 0D : Math.round(cacheTokens * 10_000D / totalPromptTokens) / 100D; }
    private List<Distribution> distribution(List<RequestAuditLog> rows, java.util.function.Function<RequestAuditLog, String> field) {
        Map<String, List<RequestAuditLog>> groups = new LinkedHashMap<>();
        rows.forEach(row -> { String raw = field.apply(row); if (!StringUtils.hasText(raw)) return; for (String name : raw.split(",")) groups.computeIfAbsent(name, ignored -> new java.util.ArrayList<>()).add(row); });
        return groups.entrySet().stream().map(entry -> { Totals totals = totals(entry.getValue()); return new Distribution(entry.getKey(), totals.requests(), totals.totalTokens(), totals.cacheTokens(), totals.cacheHitRate()); })
                .sorted(Comparator.comparingLong(Distribution::totalTokens).reversed()).toList();
    }

    public record Totals(long requests, long inputTokens, long outputTokens, long totalTokens, long cacheTokens, double cacheHitRate) { }
    public record Distribution(String name, long requests, long totalTokens, long cacheTokens, double cacheHitRate) { }
    public record UsageResponse(Totals totals, List<Distribution> providers, List<Distribution> upstreamAccounts, List<Distribution> modelGroups, List<Distribution> models, List<Distribution> userGroups, List<Distribution> users) { }
    public record FilterOptions(List<String> users, List<String> providers, List<String> apiTypes) { }
}
