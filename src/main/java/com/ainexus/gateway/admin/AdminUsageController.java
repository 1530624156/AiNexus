package com.ainexus.gateway.admin;

import com.ainexus.gateway.common.BizException;
import com.ainexus.gateway.mapper.RequestAuditLogMapper;
import com.ainexus.gateway.service.DailyTokenActivityService;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Administrative usage analytics based on database-side audit aggregations. */
@RestController
@RequestMapping("/api/admin/usage")
@RequiredArgsConstructor
public class AdminUsageController {
    private final RequestAuditLogMapper auditMapper;
    private final DailyTokenActivityService dailyTokenActivityService;

    @GetMapping
    public UsageResponse usage(@RequestParam(required = false) String user,
                               @RequestParam(required = false) String provider,
                               @RequestParam(required = false) String apiType,
                               @RequestParam(defaultValue = "today") String range,
                               @RequestParam(required = false) LocalDate startDate,
                               @RequestParam(required = false) LocalDate endDate) {
        TimeRange timeRange = timeRange(range, startDate, endDate);
        RequestAuditLogMapper.AuditUsageTotals totals = auditMapper.usageTotals(user, provider, apiType, timeRange.start(), timeRange.end());
        Map<Dimension, List<Distribution>> distributions = distributions(auditMapper.usageDistributions(user, provider, apiType, timeRange.start(), timeRange.end()));
        return new UsageResponse(totals(totals), distributions.get(Dimension.providers), distributions.get(Dimension.channels),
                distributions.get(Dimension.upstreamAccounts), distributions.get(Dimension.modelGroups), distributions.get(Dimension.models),
                distributions.get(Dimension.userGroups), distributions.get(Dimension.users));
    }

    @GetMapping("/token-activity")
    public DailyTokenActivityService.Activity tokenActivity(@RequestParam(required = false) String user,
                                                             @RequestParam(required = false) String provider,
                                                             @RequestParam(required = false) String apiType,
                                                             @RequestParam(defaultValue = "today") String range,
                                                             @RequestParam(required = false) LocalDate startDate,
                                                             @RequestParam(required = false) LocalDate endDate) {
        TimeRange timeRange = timeRange(range, startDate, endDate);
        return dailyTokenActivityService.activity(DailyTokenActivityService.Filter.admin(user, provider, apiType),
                timeRange.start().toLocalDate(), timeRange.end().minusNanos(1).toLocalDate());
    }

    @GetMapping("/filters")
    public FilterOptions filters() {
        Map<String, List<String>> options = new LinkedHashMap<>();
        options.put("users", new ArrayList<>());
        options.put("providers", new ArrayList<>());
        options.put("apiTypes", new ArrayList<>());
        auditMapper.usageFilterOptions().forEach(option -> options.get(option.dimension()).add(option.name()));
        options.values().forEach(values -> values.sort(String::compareTo));
        return new FilterOptions(options.get("users"), options.get("providers"), options.get("apiTypes"));
    }

    private TimeRange timeRange(String range, LocalDate startDate, LocalDate endDate) {
        LocalDateTime now = LocalDateTime.now();
        return switch (range) {
            case "24h" -> new TimeRange(now.minusHours(24), now);
            case "week" -> new TimeRange(now.toLocalDate().with(java.time.DayOfWeek.MONDAY).atStartOfDay(), now);
            case "month" -> new TimeRange(now.toLocalDate().with(TemporalAdjusters.firstDayOfMonth()).atStartOfDay(), now);
            case "30d" -> new TimeRange(now.minusDays(30), now);
            case "custom" -> customTimeRange(startDate, endDate);
            case "today" -> new TimeRange(now.toLocalDate().atStartOfDay(), now);
            default -> throw BizException.badRequest("invalid_usage_range", "range must be 24h, today, week, month, 30d or custom");
        };
    }

    private TimeRange customTimeRange(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null || endDate.isBefore(startDate)) {
            throw BizException.badRequest("invalid_usage_date_range", "Custom range requires startDate and endDate, with endDate on or after startDate");
        }
        if (startDate.plusYears(2).isBefore(endDate)) {
            throw BizException.badRequest("invalid_usage_date_range", "Custom range cannot exceed two years");
        }
        return new TimeRange(startDate.atStartOfDay(), endDate.plusDays(1).atStartOfDay());
    }

    private Map<Dimension, List<Distribution>> distributions(List<RequestAuditLogMapper.AuditUsageDistribution> rows) {
        Map<Dimension, Map<String, Totals>> grouped = new EnumMap<>(Dimension.class);
        for (Dimension dimension : Dimension.values()) grouped.put(dimension, new LinkedHashMap<>());
        for (RequestAuditLogMapper.AuditUsageDistribution row : rows) {
            Dimension dimension = Dimension.valueOf(row.dimension());
            String[] names = (dimension == Dimension.modelGroups || dimension == Dimension.userGroups) ? row.name().split(",") : new String[]{row.name()};
            for (String rawName : names) {
                String name = rawName.trim();
                if (!StringUtils.hasText(name)) continue;
                grouped.get(dimension).merge(name, totals(row), Totals::add);
            }
        }
        Map<Dimension, List<Distribution>> result = new EnumMap<>(Dimension.class);
        grouped.forEach((dimension, values) -> result.put(dimension, values.entrySet().stream()
                .map(entry -> new Distribution(entry.getKey(), entry.getValue().requests(), entry.getValue().totalTokens(), entry.getValue().cacheTokens(), entry.getValue().cacheHitRate()))
                .sorted(Comparator.comparingLong(Distribution::totalTokens).reversed()).toList()));
        return result;
    }

    private Totals totals(RequestAuditLogMapper.AuditUsageTotals value) {
        return new Totals(value.requests(), value.inputTokens(), value.outputTokens(), value.totalTokens(), value.cacheTokens(), cacheHitRate(value.inputTokens(), value.cacheTokens()));
    }

    private Totals totals(RequestAuditLogMapper.AuditUsageDistribution value) {
        return new Totals(value.requests(), value.inputTokens(), 0, value.totalTokens(), value.cacheTokens(), cacheHitRate(value.inputTokens(), value.cacheTokens()));
    }

    private static double cacheHitRate(long inputTokens, long cacheTokens) {
        long totalPromptTokens = inputTokens + cacheTokens;
        return totalPromptTokens <= 0 ? 0D : Math.round(cacheTokens * 10_000D / totalPromptTokens) / 100D;
    }

    private enum Dimension { providers, channels, upstreamAccounts, modelGroups, models, userGroups, users }
    private record TimeRange(LocalDateTime start, LocalDateTime end) { }
    public record Totals(long requests, long inputTokens, long outputTokens, long totalTokens, long cacheTokens, double cacheHitRate) {
        private Totals add(Totals other) {
            long input = inputTokens + other.inputTokens;
            long cache = cacheTokens + other.cacheTokens;
            return new Totals(requests + other.requests, input, outputTokens + other.outputTokens, totalTokens + other.totalTokens, cache, AdminUsageController.cacheHitRate(input, cache));
        }
    }
    public record Distribution(String name, long requests, long totalTokens, long cacheTokens, double cacheHitRate) { }
    public record UsageResponse(Totals totals, List<Distribution> providers, List<Distribution> channels, List<Distribution> upstreamAccounts, List<Distribution> modelGroups, List<Distribution> models, List<Distribution> userGroups, List<Distribution> users) { }
    public record FilterOptions(List<String> users, List<String> providers, List<String> apiTypes) { }
}
