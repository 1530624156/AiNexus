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

    public void check(Long accountId) {
        if (accountId == null) return;
        List<Long> groupIds = memberMapper.selectList(new LambdaQueryWrapper<UserGroupMember>().eq(UserGroupMember::getAccountId, accountId))
                .stream().map(UserGroupMember::getUserGroupId).toList();
        if (groupIds.isEmpty()) return;
        for (UserGroup group : groupMapper.selectBatchIds(groupIds)) {
            long quota = group.getTokenQuota() == null ? 0 : group.getTokenQuota();
            if (quota <= 0) continue;
            long used = usedByGroup(group.getId(), periodStart(group.getQuotaPeriod()));
            if (used >= quota) {
                throw BizException.tokenQuotaExceeded("User group token quota exhausted: " + group.getName());
            }
        }
    }

    public long usedByGroup(Long groupId, LocalDateTime periodStart) {
        List<Long> members = memberMapper.selectList(new LambdaQueryWrapper<UserGroupMember>().eq(UserGroupMember::getUserGroupId, groupId))
                .stream().map(UserGroupMember::getAccountId).toList();
        if (members.isEmpty()) return 0;
        return auditMapper.selectList(new LambdaQueryWrapper<RequestAuditLog>().in(RequestAuditLog::getAccountId, members).ge(RequestAuditLog::getCreateTime, periodStart))
                .stream().map(RequestAuditLog::getTotalTokens).filter(java.util.Objects::nonNull).mapToLong(Long::longValue).sum();
    }

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
