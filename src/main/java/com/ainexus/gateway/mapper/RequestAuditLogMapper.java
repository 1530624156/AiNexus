package com.ainexus.gateway.mapper;

import com.ainexus.gateway.entity.RequestAuditLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/** 异步请求审计日志 Mapper。 */
@Mapper
public interface RequestAuditLogMapper extends BaseMapper<RequestAuditLog> {
    @Select("<script>SELECT COALESCE(SUM(prompt_tokens), 0) FROM request_audit_log "
            + "WHERE create_time &gt;= #{periodStart} AND app_name IN "
            + "<foreach collection='appNames' item='name' open='(' separator=',' close=')'>#{name}</foreach></script>")
    Long sumPromptTokensByAppNamesSince(@Param("appNames") List<String> appNames,
                                        @Param("periodStart") LocalDateTime periodStart);
}
