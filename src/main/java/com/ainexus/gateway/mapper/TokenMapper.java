package com.ainexus.gateway.mapper;

import com.ainexus.gateway.entity.Token;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 内部应用令牌 Mapper。 */
@Mapper
public interface TokenMapper extends BaseMapper<Token> {
}
