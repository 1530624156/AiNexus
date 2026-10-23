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

    public List<Quota> quotas(Long accountId) {
        List<Long> ids = memberMapper.selectList(new LambdaQueryWrapper<UserGroupMember>().eq(UserGroupMember::getAccountId, accountId))
                .stream().map(UserGroupMember::getUserGroupId).toList();
        if (ids.isEmpty()) return List.of();
        return groupMapper.selectBatchIds(ids).stream().map(group -> quota(accountId, group)).toList();
    }

    private Quota quota(Long accountId, UserGroup group) {
        LocalDateTime start = groupTokenQuotaService.periodStart(group.getQuotaPeriod());
        long personalUsed = groupTokenQuotaService.usedByAccount(accountId, start);
        long personalQuota = group.getMemberTokenQuota() == null ? 0 : group.getMemberTokenQuota();
        long groupQuota = group.getTokenQuota() == null ? 0 : group.getTokenQuota();
        long groupUsed = groupQuota == 0 ? 0 : groupTokenQuotaService.usedByGroup(group.getId(), start);
        return new Quota(group.getName(), group.getQuotaPeriod(),
                limit(personalQuota), personalUsed, remaining(personalQuota, personalUsed),
                limit(groupQuota), groupUsed, remaining(groupQuota, groupUsed));
    }

    private Long limit(long value) { return value == 0 ? null : value; }
    private Long remaining(long quota, long used) { return quota == 0 ? null : Math.max(0, quota - used); }
    public record Quota(String userGroup, String period,
                        Long personalQuota, long personalUsed, Long personalRemaining,
                        Long groupQuota, long groupUsed, Long groupRemaining) { }
}
