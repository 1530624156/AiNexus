package com.ainexus.gateway.mapper;

import com.ainexus.gateway.entity.UserGroupModelGroup;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserGroupModelGroupMapper extends BaseMapper<UserGroupModelGroup> {
    @Delete("DELETE FROM user_group_model_group WHERE user_group_id = #{groupId}")
    int deleteByGroupId(Long groupId);
}
