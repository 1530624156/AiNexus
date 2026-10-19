package com.ainexus.gateway.service;

import com.ainexus.gateway.entity.RequestAuditLog;
import com.ainexus.gateway.mapper.RequestAuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Asynchronous request audit persistence. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {
    private final RequestAuditLogMapper requestAuditLogMapper;
    private final VirtualThreadTaskExecutor virtualThreadTaskExecutor;

    public void submit(AuditEvent event) {
        virtualThreadTaskExecutor.submit(() -> {
            try {
                RequestAuditLog row = new RequestAuditLog();
                row.setRequestId(truncate(event.requestId(), 64));
                row.setAppName(truncate(event.appName(), 128));
                row.setApiKeyName(truncate(event.apiKeyName(), 128));
                row.setAccountId(event.accountId());
                row.setUserName(truncate(event.userName(), 128));
                row.setRequestModel(truncate(event.requestModel(), 128));
                row.setUpstreamModel(truncate(event.upstreamModel(), 128));
                row.setTargetChannel(truncate(event.targetChannel(), 128));
                row.setProvider(truncate(event.provider(), 64));
                row.setUpstreamAccount(truncate(event.upstreamAccount(), 128));
                row.setApiType(truncate(event.apiType(), 64));
                row.setStream(event.stream() ? 1 : 0);
                row.setUpstreamEndpoint(truncate(event.upstreamEndpoint(), 512));
                row.setProtocolPath(truncate(event.protocolPath(), 256));
                row.setModelGroupNames(truncate(event.modelGroupNames(), 1000));
                row.setUserGroupNames(truncate(event.userGroupNames(), 1000));
                row.setPromptTokens(event.inputTokens() == null ? null : event.inputTokens().intValue());
                row.setInputTokens(zero(event.inputTokens()));
                row.setOutputTokens(zero(event.outputTokens()));
                row.setTotalTokens(event.totalTokens() == null ? zero(event.inputTokens()) + zero(event.outputTokens()) : zero(event.totalTokens()));
                row.setCacheTokens(zero(event.cacheTokens()));
                row.setStatusCode(event.statusCode());
                row.setUpstreamStatusCode(event.upstreamStatusCode());
                row.setLatencyMs(event.latencyMs());
                row.setFirstTokenMs(event.firstTokenMs());
                row.setQueueMs(zero(event.queueMs()));
                row.setUpstreamLatencyMs(event.upstreamLatencyMs());
                row.setErrorMsg(truncate(event.errorMessage(), 1000));
                row.setErrorCode(truncate(event.errorCode(), 128));
                requestAuditLogMapper.insert(row);
            } catch (Exception e) {
                log.warn("[audit] async insert failed: {}", e.toString());
            }
        });
    }

    private long zero(Long value) { return value == null ? 0L : value; }
    private String truncate(String value, int limit) { return value == null || value.length() <= limit ? value : value.substring(0, limit); }

    public record AuditEvent(String requestId, String appName, String apiKeyName, Long accountId, String userName,
                             String requestModel, String upstreamModel, String targetChannel, String provider,
                             String upstreamAccount, String apiType, boolean stream, String upstreamEndpoint,
                             String protocolPath, String modelGroupNames, String userGroupNames, Long inputTokens,
                             Long outputTokens, Long totalTokens, Long cacheTokens, int statusCode,
                             Integer upstreamStatusCode, long latencyMs, Long firstTokenMs, Long queueMs,
                             Long upstreamLatencyMs, String errorCode, String errorMessage) { }
}
