package com.ainexus.gateway.service;

import com.ainexus.gateway.mapper.RequestAuditLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/** Produces GitHub-style calendar data from the immutable request audit trail. */
@Service
@RequiredArgsConstructor
public class DailyTokenActivityService {
    private final RequestAuditLogMapper auditMapper;

    public Activity activity(Filter filter, LocalDate endDate) {
        LocalDate end = endDate == null ? LocalDate.now() : endDate;
        LocalDate start = end.minusDays(363);
        List<Day> days = auditMapper.dailyTokenActivity(filter.accountId(), filter.username(), filter.appName(),
                        filter.requestModel(), filter.provider(), filter.apiType(), start, end).stream()
                .map(row -> new Day(row.day().toString(), row.totalTokens(), row.requests()))
                .toList();
        long totalTokens = days.stream().mapToLong(Day::totalTokens).sum();
        long totalRequests = days.stream().mapToLong(Day::requests).sum();
        return new Activity(start.toString(), end.toString(), totalTokens, totalRequests, days.size(), days);
    }

    public record Filter(Long accountId, String username, String appName, String requestModel, String provider, String apiType) {
        public static Filter account(Long accountId, String appName, String requestModel) {
            return new Filter(accountId, null, appName, requestModel, null, null);
        }
        public static Filter admin(String username, String provider, String apiType) {
            return new Filter(null, username, null, null, provider, apiType);
        }
    }
    public record Activity(String startDate, String endDate, long totalTokens, long totalRequests,
                           int activeDays, List<Day> days) { }
    public record Day(String date, long totalTokens, long requests) { }
}
