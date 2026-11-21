package com.ainexus.gateway.service;

import com.ainexus.gateway.common.BizException;
import com.ainexus.gateway.entity.RequestAuditLog;
import com.ainexus.gateway.entity.UserGroup;
import com.ainexus.gateway.entity.UserGroupMember;
import com.ainexus.gateway.mapper.RequestAuditLogMapper;
import com.ainexus.gateway.mapper.UserGroupMapper;
import com.ainexus.gateway.mapper.UserGroupMemberMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

/** Enforces aggregate user-group token quotas before a gateway request is sent upstream. */
@Service
@RequiredArgsConstructor
public class UserGroupTokenQuotaService {
    private final UserGroupMemberMapper memberMapper;
    private final UserGroupMapper groupMapper;
    private final RequestAuditLogMapper auditMapper;
    private final GatewayRuntimeSettingsService settingsService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public void check(Long accountId) {
        if (accountId == null || settingsService.get().crazyModeEnabled()) return;
        List<Long> groupIds = memberMapper.selectList(new LambdaQueryWrapper<UserGroupMember>().eq(UserGroupMember::getAccountId, accountId))
                .stream().map(UserGroupMember::getUserGroupId).toList();
        if (groupIds.isEmpty()) return;
        for (UserGroup group : groupMapper.selectBatchIds(groupIds)) {
            LocalDateTime start = periodStart(group.getQuotaPeriod());
            long memberQuota = group.getMemberTokenQuota() == null ? 0 : group.getMemberTokenQuota();
            if (memberQuota > 0 && usedByAccount(accountId, start) >= memberQuota) {
                throw BizException.tokenQuotaExceeded("User group member token quota exhausted: " + group.getName());
            }

            long groupQuota = effectiveGroupQuota(group, LocalDateTime.now());
            if (groupQuota > 0 && usedByGroup(group.getId(), start) >= groupQuota) {
                throw BizException.tokenQuotaExceeded("User group token quota exhausted: " + group.getName());
            }
        }
    }

    public long effectiveGroupQuota(UserGroup group, LocalDateTime now) {
        return quotaSnapshot(group, now).effectiveQuota();
    }

    public GroupQuotaSnapshot quotaSnapshot(UserGroup group, LocalDateTime now) {
        long baseQuota = group.getTokenQuota() == null ? 0 : group.getTokenQuota();
        LocalDateTime periodStart = periodStart(group.getQuotaPeriod());
        if (baseQuota == 0 || !"DAY".equals(group.getQuotaPeriod()) || group.getQuotaSchedule() == null || group.getQuotaSchedule().isBlank()) {
            return new GroupQuotaSnapshot(baseQuota, baseQuota, periodStart, null);
        }
        try {
            com.fasterxml.jackson.databind.JsonNode slots = objectMapper.readTree(group.getQuotaSchedule());
            java.time.LocalTime currentTime = now.toLocalTime();
            long dailyTotalQuota = baseQuota;
            long effectiveQuota = baseQuota;
            java.time.LocalTime nextStart = null;
            for (com.fasterxml.jackson.databind.JsonNode slot : slots) {
                java.time.LocalTime time = java.time.LocalTime.parse(slot.path("time").asText());
                long amount = slot.path("amount").asLong();
                if (amount < 0) continue;
                dailyTotalQuota = Math.addExact(dailyTotalQuota, amount);
                if (!currentTime.isBefore(time)) effectiveQuota = Math.addExact(effectiveQuota, amount);
                else if (nextStart == null) nextStart = time;
            }
            return new GroupQuotaSnapshot(dailyTotalQuota, effectiveQuota, periodStart, nextStart);
        } catch (Exception ignored) {
            return new GroupQuotaSnapshot(baseQuota, baseQuota, periodStart, null);
        }
    }

    public long usedByAccount(Long accountId, LocalDateTime periodStart) {
        return auditMapper.selectList(new LambdaQueryWrapper<RequestAuditLog>()
                        .eq(RequestAuditLog::getAccountId, accountId)
                        .ge(RequestAuditLog::getCreateTime, periodStart))
                .stream().map(RequestAuditLog::getTotalTokens).filter(java.util.Objects::nonNull).mapToLong(Long::longValue).sum();
    }

    public long usedByGroup(Long groupId, LocalDateTime periodStart) {
        List<Long> members = memberMapper.selectList(new LambdaQueryWrapper<UserGroupMember>().eq(UserGroupMember::getUserGroupId, groupId))
                .stream().map(UserGroupMember::getAccountId).toList();
        if (members.isEmpty()) return 0;
        return auditMapper.selectList(new LambdaQueryWrapper<RequestAuditLog>().in(RequestAuditLog::getAccountId, members).ge(RequestAuditLog::getCreateTime, periodStart))
                .stream().map(RequestAuditLog::getTotalTokens).filter(java.util.Objects::nonNull).mapToLong(Long::longValue).sum();
    }

    public record GroupQuotaSnapshot(long dailyTotalQuota, long effectiveQuota, LocalDateTime periodStart,
                                     java.time.LocalTime nextWindowStart) { }

    public LocalDateTime periodStart(String period) {
        LocalDateTime now = LocalDateTime.now();
        return switch (period) {
            case "DAY" -> now.toLocalDate().atStartOfDay();
            case "WEEK" -> now.toLocalDate().with(java.time.DayOfWeek.MONDAY).atStartOfDay();
            case "YEAR" -> now.toLocalDate().with(TemporalAdjusters.firstDayOfYear()).atStartOfDay();
            default -> now.toLocalDate().with(TemporalAdjusters.firstDayOfMonth()).atStartOfDay();
        };
    }
}
