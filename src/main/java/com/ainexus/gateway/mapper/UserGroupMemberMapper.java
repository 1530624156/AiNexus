package com.ainexus.gateway.mapper;

import com.ainexus.gateway.entity.UserGroupMember;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserGroupMemberMapper extends BaseMapper<UserGroupMember> {
    @Delete("DELETE FROM user_group_member WHERE user_group_id = #{groupId}")
    int deleteByGroupId(Long groupId);
}
