package com.ainexus.gateway.service;

import com.ainexus.gateway.entity.RequestAuditLog;
import com.ainexus.gateway.mapper.RequestAuditLogMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Asynchronous request audit persistence.
 *
 * <p>请求分两步落库：进入转发链路时先写一条 {@code status_code = 0} 的“处理中”记录，结束时按
 * {@code request_id} 更新同一条记录。这样卡在全局排队、渠道并发许可或上游超时里的请求在后台可见——
 * 否则请求在结束前完全没有记录，表现为“客户端无响应、后台也没有调用日志”，运维无法分辨是没到网关、
 * 排队中还是上游挂住。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    /** 处理中占位状态；真实响应不会使用 0。 */
    public static final int STATUS_PENDING = 0;

    private final RequestAuditLogMapper requestAuditLogMapper;
    private final VirtualThreadTaskExecutor virtualThreadTaskExecutor;

    /** 一次性写入：用于鉴权/限流在进入 Controller 之前就被拒绝的请求。 */
    public void submit(AuditEvent event) {
        virtualThreadTaskExecutor.submit(() -> insert(toRow(event), "insert"));
    }

    /**
     * 请求进入转发链路：先落一条“处理中”记录。
     *
     * @return 初始插入的完成信号，交给 {@link #complete(AuditEvent, CompletableFuture)} 等待，
     *         避免更新语句先于插入执行（插入丢失时更新影响 0 行，后续会走补写分支）
     */
    public CompletableFuture<Void> start(AuditEvent event) {
        CompletableFuture<Void> inserted = new CompletableFuture<>();
        virtualThreadTaskExecutor.submit(() -> {
            try {
                insert(toRow(event), "start insert");
            } finally {
                inserted.complete(null);
            }
        });
        return inserted;
    }

    /** 请求结束：更新“处理中”记录；初始记录缺失（DB 抖动或请求在解析模型前就失败）时补写完整记录。 */
    public void complete(AuditEvent event, CompletableFuture<Void> started) {
        virtualThreadTaskExecutor.submit(() -> {
            awaitQuietly(started);
            try {
                int updated = requestAuditLogMapper.update(toRow(event),
                        new LambdaUpdateWrapper<RequestAuditLog>().eq(RequestAuditLog::getRequestId, event.requestId()));
                if (updated == 0) {
                    insert(toRow(event), "fallback insert");
                }
            } catch (Exception e) {
                log.warn("[audit] async update failed: {}", e.toString());
            }
        });
    }

    /** 构造“处理中”事件：只包含进入路由前已知的字段，其余由结束时的 {@link #complete} 补齐。 */
    public static AuditEvent pending(String requestId, String appName, String apiKeyName, Long accountId,
                                     String requestModel, boolean stream, String apiType) {
        return new AuditEvent(requestId, appName, apiKeyName, accountId, null, requestModel, null, null, null, null,
                apiType, stream, null, null, null, null, null, null, null, null,
                STATUS_PENDING, null, 0L, null, 0L, null, null, null);
    }

    private void insert(RequestAuditLog row, String label) {
        try {
            requestAuditLogMapper.insert(row);
        } catch (Exception e) {
            log.warn("[audit] async {} failed: {}", label, e.toString());
        }
    }

    private void awaitQuietly(CompletableFuture<Void> started) {
        if (started == null) {
            return;
        }
        try {
            started.get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // 初始记录没写成也要继续：更新会命中 0 行，随后补写完整记录。
        }
    }

    private RequestAuditLog toRow(AuditEvent event) {
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
        long inputTokens = zero(event.inputTokens());
        long outputTokens = zero(event.outputTokens());
        long cacheTokens = zero(event.cacheTokens());
        row.setPromptTokens(event.inputTokens() == null ? null : event.inputTokens().intValue());
        row.setInputTokens(inputTokens);
        row.setOutputTokens(outputTokens);
        row.setCacheTokens(cacheTokens);
        row.setTotalTokens(inputTokens + outputTokens + cacheTokens);
        row.setStatusCode(event.statusCode());
        row.setUpstreamStatusCode(event.upstreamStatusCode());
        row.setLatencyMs(event.latencyMs());
        row.setFirstTokenMs(event.firstTokenMs());
        row.setQueueMs(zero(event.queueMs()));
        row.setUpstreamLatencyMs(event.upstreamLatencyMs());
        row.setErrorMsg(truncate(event.errorMessage(), 1000));
        row.setErrorCode(truncate(event.errorCode(), 128));
        return row;
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