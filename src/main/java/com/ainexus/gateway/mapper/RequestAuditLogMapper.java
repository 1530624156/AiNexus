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

    @Select("SELECT COUNT(*) AS requests, COALESCE(SUM(total_tokens), 0) AS totalTokens, "
            + "COUNT(DISTINCT account_id) AS activeUsers FROM request_audit_log WHERE create_time >= #{start}")
    AuditOverviewSummary overviewSince(@Param("start") LocalDateTime start);

    @Select("<script>SELECT COUNT(*) AS requests, COALESCE(SUM(input_tokens), 0) AS inputTokens, "
            + "COALESCE(SUM(output_tokens), 0) AS outputTokens, COALESCE(SUM(total_tokens), 0) AS totalTokens, "
            + "COALESCE(SUM(cache_tokens), 0) AS cacheTokens FROM request_audit_log <where>"
            + "<if test='user != null and user != \"\"'> AND user_name = #{user}</if>"
            + "<if test='provider != null and provider != \"\"'> AND provider = #{provider}</if>"
            + "<if test='apiType != null and apiType != \"\"'> AND api_type = #{apiType}</if>"
            + "</where></script>")
    AuditUsageTotals usageTotals(@Param("user") String user, @Param("provider") String provider, @Param("apiType") String apiType);

    @Select("<script>"
            + "SELECT 'providers' AS dimension, provider AS name, COUNT(*) AS requests, COALESCE(SUM(input_tokens), 0) AS inputTokens, COALESCE(SUM(total_tokens), 0) AS totalTokens, COALESCE(SUM(cache_tokens), 0) AS cacheTokens FROM request_audit_log <where><if test='user != null and user != \"\"'> AND user_name = #{user}</if><if test='provider != null and provider != \"\"'> AND provider = #{provider}</if><if test='apiType != null and apiType != \"\"'> AND api_type = #{apiType}</if> AND provider IS NOT NULL AND provider != ''</where> GROUP BY provider "
            + "UNION ALL SELECT 'upstreamAccounts', upstream_account, COUNT(*), COALESCE(SUM(input_tokens), 0), COALESCE(SUM(total_tokens), 0), COALESCE(SUM(cache_tokens), 0) FROM request_audit_log <where><if test='user != null and user != \"\"'> AND user_name = #{user}</if><if test='provider != null and provider != \"\"'> AND provider = #{provider}</if><if test='apiType != null and apiType != \"\"'> AND api_type = #{apiType}</if> AND upstream_account IS NOT NULL AND upstream_account != ''</where> GROUP BY upstream_account "
            + "UNION ALL SELECT 'modelGroups', model_group_names, COUNT(*), COALESCE(SUM(input_tokens), 0), COALESCE(SUM(total_tokens), 0), COALESCE(SUM(cache_tokens), 0) FROM request_audit_log <where><if test='user != null and user != \"\"'> AND user_name = #{user}</if><if test='provider != null and provider != \"\"'> AND provider = #{provider}</if><if test='apiType != null and apiType != \"\"'> AND api_type = #{apiType}</if> AND model_group_names IS NOT NULL AND model_group_names != ''</where> GROUP BY model_group_names "
            + "UNION ALL SELECT 'models', request_model, COUNT(*), COALESCE(SUM(input_tokens), 0), COALESCE(SUM(total_tokens), 0), COALESCE(SUM(cache_tokens), 0) FROM request_audit_log <where><if test='user != null and user != \"\"'> AND user_name = #{user}</if><if test='provider != null and provider != \"\"'> AND provider = #{provider}</if><if test='apiType != null and apiType != \"\"'> AND api_type = #{apiType}</if> AND request_model IS NOT NULL AND request_model != ''</where> GROUP BY request_model "
            + "UNION ALL SELECT 'userGroups', user_group_names, COUNT(*), COALESCE(SUM(input_tokens), 0), COALESCE(SUM(total_tokens), 0), COALESCE(SUM(cache_tokens), 0) FROM request_audit_log <where><if test='user != null and user != \"\"'> AND user_name = #{user}</if><if test='provider != null and provider != \"\"'> AND provider = #{provider}</if><if test='apiType != null and apiType != \"\"'> AND api_type = #{apiType}</if> AND user_group_names IS NOT NULL AND user_group_names != ''</where> GROUP BY user_group_names "
            + "UNION ALL SELECT 'users', user_name, COUNT(*), COALESCE(SUM(input_tokens), 0), COALESCE(SUM(total_tokens), 0), COALESCE(SUM(cache_tokens), 0) FROM request_audit_log <where><if test='user != null and user != \"\"'> AND user_name = #{user}</if><if test='provider != null and provider != \"\"'> AND provider = #{provider}</if><if test='apiType != null and apiType != \"\"'> AND api_type = #{apiType}</if> AND user_name IS NOT NULL AND user_name != ''</where> GROUP BY user_name"
            + "</script>")
    List<AuditUsageDistribution> usageDistributions(@Param("user") String user, @Param("provider") String provider, @Param("apiType") String apiType);

    @Select("SELECT 'users' AS dimension, user_name AS name FROM request_audit_log WHERE user_name IS NOT NULL AND user_name != '' GROUP BY user_name "
            + "UNION ALL SELECT 'providers', provider FROM request_audit_log WHERE provider IS NOT NULL AND provider != '' GROUP BY provider "
            + "UNION ALL SELECT 'apiTypes', api_type FROM request_audit_log WHERE api_type IS NOT NULL AND api_type != '' GROUP BY api_type")
    List<AuditFilterOption> usageFilterOptions();

    @Select("<script>SELECT DATE(create_time) AS day, COALESCE(SUM(total_tokens), 0) AS totalTokens, COUNT(*) AS requests "
            + "FROM request_audit_log WHERE create_time &gt;= #{start} AND create_time &lt; DATE_ADD(#{end}, INTERVAL 1 DAY) "
            + "<if test='accountId != null'>AND account_id = #{accountId} </if>"
            + "<if test='username != null and username != \"\"'>AND user_name = #{username} </if>"
            + "<if test='appName != null and appName != \"\"'>AND app_name = #{appName} </if>"
            + "<if test='requestModel != null and requestModel != \"\"'>AND request_model = #{requestModel} </if>"
            + "<if test='provider != null and provider != \"\"'>AND provider = #{provider} </if>"
            + "<if test='apiType != null and apiType != \"\"'>AND api_type = #{apiType} </if>"
            + "GROUP BY DATE(create_time) ORDER BY day</script>")
    List<DailyTokenActivity> dailyTokenActivity(@Param("accountId") Long accountId, @Param("username") String username,
                                                 @Param("appName") String appName, @Param("requestModel") String requestModel,
                                                 @Param("provider") String provider, @Param("apiType") String apiType,
                                                 @Param("start") java.time.LocalDate start, @Param("end") java.time.LocalDate end);

    record AuditOverviewSummary(long requests, long totalTokens, long activeUsers) { }
    record AuditUsageTotals(long requests, long inputTokens, long outputTokens, long totalTokens, long cacheTokens) { }
    record AuditUsageDistribution(String dimension, String name, long requests, long inputTokens, long totalTokens, long cacheTokens) { }
    record AuditFilterOption(String dimension, String name) { }
    record DailyTokenActivity(java.time.LocalDate day, long totalTokens, long requests) { }
}
