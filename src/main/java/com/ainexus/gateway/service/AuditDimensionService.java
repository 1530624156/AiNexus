package com.ainexus.gateway.service;

import com.ainexus.gateway.entity.Account;
import com.ainexus.gateway.entity.ModelGroup;
import com.ainexus.gateway.entity.ModelGroupModel;
import com.ainexus.gateway.entity.UserGroup;
import com.ainexus.gateway.entity.UserGroupMember;
import com.ainexus.gateway.entity.UserGroupModelGroup;
import com.ainexus.gateway.mapper.AccountMapper;
import com.ainexus.gateway.mapper.ModelGroupMapper;
import com.ainexus.gateway.mapper.ModelGroupModelMapper;
import com.ainexus.gateway.mapper.UserGroupMapper;
import com.ainexus.gateway.mapper.UserGroupMemberMapper;
import com.ainexus.gateway.mapper.UserGroupModelGroupMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** Resolves immutable audit dimension labels at request time. */
@Service
@RequiredArgsConstructor
public class AuditDimensionService {
    private final AccountMapper accountMapper;
    private final UserGroupMemberMapper memberMapper;
    private final UserGroupMapper userGroupMapper;
    private final UserGroupModelGroupMapper groupModelGroupMapper;
    private final ModelGroupMapper modelGroupMapper;
    private final ModelGroupModelMapper modelGroupModelMapper;

    public Dimensions resolve(Long accountId, String model) {
        if (accountId == null) return new Dimensions(null, null, null);
        Account account = accountMapper.selectById(accountId);
        List<Long> groupIds = memberMapper.selectList(new LambdaQueryWrapper<UserGroupMember>().eq(UserGroupMember::getAccountId, accountId))
                .stream().map(UserGroupMember::getUserGroupId).toList();
        if (groupIds.isEmpty()) return new Dimensions(accountId, account == null ? null : account.getUsername(), null);
        String userGroups = join(userGroupMapper.selectBatchIds(groupIds).stream().map(UserGroup::getName).toList());
        List<Long> modelGroupIds = groupModelGroupMapper.selectList(new LambdaQueryWrapper<UserGroupModelGroup>().in(UserGroupModelGroup::getUserGroupId, groupIds))
                .stream().map(UserGroupModelGroup::getModelGroupId).distinct().toList();
        if (modelGroupIds.isEmpty()) return new Dimensions(accountId, account == null ? null : account.getUsername(), userGroups, null);
        List<Long> matching = modelGroupModelMapper.selectList(new LambdaQueryWrapper<ModelGroupModel>().in(ModelGroupModel::getModelGroupId, modelGroupIds).eq(ModelGroupModel::getModelName, model))
                .stream().map(ModelGroupModel::getModelGroupId).distinct().toList();
        String modelGroups = matching.isEmpty() ? null : join(modelGroupMapper.selectBatchIds(matching).stream().map(ModelGroup::getName).toList());
        return new Dimensions(accountId, account == null ? null : account.getUsername(), userGroups, modelGroups);
    }

    private String join(List<String> names) { return names.isEmpty() ? null : String.join(",", names); }
    public record Dimensions(Long accountId, String userName, String userGroupNames, String modelGroupNames) {
        public Dimensions(Long accountId, String userName, String userGroupNames) { this(accountId, userName, userGroupNames, null); }
    }
}
