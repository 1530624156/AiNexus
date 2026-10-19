package com.ainexus.gateway.service;

import com.ainexus.gateway.entity.UserGroup;
import com.ainexus.gateway.entity.UserGroupMember;
import com.ainexus.gateway.mapper.UserGroupMapper;
import com.ainexus.gateway.mapper.UserGroupMemberMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Local active-request counters for user-group limits and dashboard observation. */
@Service
@RequiredArgsConstructor
public class UserGroupConcurrencyService {
    private final UserGroupMemberMapper memberMapper;
    private final UserGroupMapper groupMapper;
    private final ConcurrentHashMap<Long, AtomicInteger> active = new ConcurrentHashMap<>();

    public Permit acquire(Long accountId) {
        if (accountId == null) return Permit.NOOP;
        List<UserGroup> groups = groups(accountId);
        for (UserGroup group : groups) {
            int limit = group.getMaxConcurrent() == null ? 0 : group.getMaxConcurrent();
            if (limit > 0 && active.computeIfAbsent(group.getId(), ignored -> new AtomicInteger()).get() >= limit) {
                throw com.ainexus.gateway.common.BizException.rateLimited("User group concurrency limit reached: " + group.getName());
            }
        }
        groups.forEach(group -> active.computeIfAbsent(group.getId(), ignored -> new AtomicInteger()).incrementAndGet());
        return () -> groups.forEach(group -> active.get(group.getId()).decrementAndGet());
    }

    public List<Snapshot> snapshots() {
        return groupMapper.selectList(new LambdaQueryWrapper<UserGroup>().orderByAsc(UserGroup::getName)).stream()
                .map(group -> new Snapshot(group.getId(), group.getName(), active.getOrDefault(group.getId(), new AtomicInteger()).get(), group.getMaxConcurrent() == null ? 0 : group.getMaxConcurrent())).toList();
    }

    private List<UserGroup> groups(Long accountId) {
        List<Long> ids = memberMapper.selectList(new LambdaQueryWrapper<UserGroupMember>().eq(UserGroupMember::getAccountId, accountId)).stream().map(UserGroupMember::getUserGroupId).toList();
        return ids.isEmpty() ? List.of() : groupMapper.selectBatchIds(ids);
    }

    @FunctionalInterface public interface Permit extends AutoCloseable { Permit NOOP = () -> { }; @Override void close(); }
    public record Snapshot(Long id, String name, int active, int maxConcurrent) { }
}
