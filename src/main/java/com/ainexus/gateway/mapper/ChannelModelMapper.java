package com.ainexus.gateway.mapper;

import com.ainexus.gateway.entity.ChannelModel;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;

/** 渠道模型绑定表 Mapper。 */
@Mapper
public interface ChannelModelMapper extends BaseMapper<ChannelModel> {

    /** 替换渠道模型集合时使用物理删除，避免逻辑删除行占用 uk_channel_model 唯一键。 */
    @Delete("DELETE FROM channel_model WHERE channel_id = #{channelId}")
    int deleteBindingsByChannelId(Long channelId);
}
