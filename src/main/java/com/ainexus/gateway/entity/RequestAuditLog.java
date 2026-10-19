package com.ainexus.gateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 请求审计记录。审计写入必须由虚拟线程异步完成，不能阻塞代理/SSE 线程。 */
@Data
@TableName("request_audit_log")
public class RequestAuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String requestId;
    private String appName;
    private String apiKeyName;
    private Long accountId;
    private String userName;
    private String requestModel;
    private String upstreamModel;
    private String targetChannel;
    private String provider;
    private String upstreamAccount;
    private String apiType;
    private Integer stream;
    private String upstreamEndpoint;
    private String protocolPath;
    private String modelGroupNames;
    private String userGroupNames;
    private Integer promptTokens;
    private Long inputTokens;
    private Long outputTokens;
    private Long totalTokens;
    private Long cacheTokens;
    private Integer statusCode;
    private Integer upstreamStatusCode;
    private Long latencyMs;
    private Long firstTokenMs;
    private Long queueMs;
    private Long upstreamLatencyMs;
    private String errorMsg;
    private String errorCode;
    private LocalDateTime createTime;
}
