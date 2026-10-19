package com.ainexus.gateway.mapper;

import com.ainexus.gateway.entity.ModelGroupModel;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ModelGroupModelMapper extends BaseMapper<ModelGroupModel> {
    @Delete("DELETE FROM model_group_model WHERE model_group_id = #{modelGroupId}")
    int deleteByModelGroupId(Long modelGroupId);
}
