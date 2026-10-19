package com.ainexus.gateway.service;

import com.ainexus.gateway.entity.ModelGroupModel;
import com.ainexus.gateway.entity.UserGroupMember;
import com.ainexus.gateway.entity.UserGroupModelGroup;
import com.ainexus.gateway.mapper.ModelGroupModelMapper;
import com.ainexus.gateway.mapper.UserGroupMemberMapper;
import com.ainexus.gateway.mapper.UserGroupModelGroupMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Resolves models granted to a console USER through their assigned user groups. */
@Service
@RequiredArgsConstructor
public class ModelAuthorizationService {
    private final UserGroupMemberMapper memberMapper;
    private final UserGroupModelGroupMapper groupModelGroupMapper;
    private final ModelGroupModelMapper modelGroupModelMapper;

    public Set<String> authorizedModels(Long accountId) {
        if (accountId == null) return Set.of();
        List<Long> userGroupIds = memberMapper.selectList(new LambdaQueryWrapper<UserGroupMember>()
                        .eq(UserGroupMember::getAccountId, accountId))
                .stream().map(UserGroupMember::getUserGroupId).toList();
        if (userGroupIds.isEmpty()) return Set.of();
        List<Long> modelGroupIds = groupModelGroupMapper.selectList(new LambdaQueryWrapper<UserGroupModelGroup>()
                        .in(UserGroupModelGroup::getUserGroupId, userGroupIds))
                .stream().map(UserGroupModelGroup::getModelGroupId).distinct().toList();
        if (modelGroupIds.isEmpty()) return Set.of();
        return modelGroupModelMapper.selectList(new LambdaQueryWrapper<ModelGroupModel>()
                        .in(ModelGroupModel::getModelGroupId, modelGroupIds))
                .stream().map(ModelGroupModel::getModelName)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
    }
}
