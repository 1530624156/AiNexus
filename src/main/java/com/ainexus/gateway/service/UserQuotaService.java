package com.ainexus.gateway.service;

import com.ainexus.gateway.entity.UserGroup;
import com.ainexus.gateway.entity.UserGroupMember;
import com.ainexus.gateway.mapper.UserGroupMapper;
import com.ainexus.gateway.mapper.UserGroupMemberMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/** Computes personal and shared-group token consumption for the current quota period. */
@Service
@RequiredArgsConstructor
public class UserQuotaService {
    private final UserGroupMemberMapper memberMapper;
    private final UserGroupMapper groupMapper;
    private final UserGroupTokenQuotaService groupTokenQuotaService;
    private final GatewayRuntimeSettingsService settingsService;

    public List<Quota> quotas(Long accountId) {
        List<Long> ids = memberMapper.selectList(new LambdaQueryWrapper<UserGroupMember>().eq(UserGroupMember::getAccountId, accountId))
                .stream().map(UserGroupMember::getUserGroupId).toList();
        if (ids.isEmpty()) return List.of();
        return groupMapper.selectBatchIds(ids).stream().map(group -> quota(accountId, group)).toList();
    }

    private Quota quota(Long accountId, UserGroup group) {
        LocalDateTime start = groupTokenQuotaService.periodStart(group.getQuotaPeriod());
        if (settingsService.get().crazyModeEnabled()) {
            return new Quota(group.getName(), group.getQuotaPeriod(), null, 0, null, null, null, null, 0, null, null);
        }
        long personalUsed = groupTokenQuotaService.usedByAccount(accountId, start);
        long personalQuota = group.getMemberTokenQuota() == null ? 0 : group.getMemberTokenQuota();
        UserGroupTokenQuotaService.GroupQuotaSnapshot snapshot = groupTokenQuotaService.quotaSnapshot(group, LocalDateTime.now());
        long groupUsed = snapshot.effectiveQuota() == 0 ? 0 : groupTokenQuotaService.usedByGroup(group.getId(), start);
        return new Quota(group.getName(), group.getQuotaPeriod(),
                limit(personalQuota), personalUsed, remaining(personalQuota, personalUsed),
                limit(snapshot.dailyTotalQuota()), limit(snapshot.effectiveQuota()), limit(snapshot.effectiveQuota()), groupUsed,
                remaining(snapshot.effectiveQuota(), groupUsed), snapshot.nextWindowStart() == null ? null : snapshot.nextWindowStart().toString());
    }

    private Long limit(long value) { return value == 0 ? null : value; }
    private Long remaining(long quota, long used) { return quota == 0 ? null : Math.max(0, quota - used); }
    public record Quota(String userGroup, String period,
                        Long personalQuota, long personalUsed, Long personalRemaining,
                        Long dailyTotalGroupQuota, Long effectiveGroupQuota, Long groupQuota, long groupUsed, Long groupRemaining, String nextQuotaActivationTime) { }
}
