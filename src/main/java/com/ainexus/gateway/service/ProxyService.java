package com.ainexus.gateway.service;

import com.ainexus.gateway.common.ErrorResponses;
import com.ainexus.gateway.route.ChannelProtocol;
import com.ainexus.gateway.route.ChannelRoute;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 核心透传服务(Transparent Proxy)。
 *
 * <p>语义: 网关不解析/不重建业务报文, 只做三件事:
 * <ol>
 *   <li>读取客户端请求体(为解析 model/stream 字段必须一次性读入, 原文保存)</li>
 *   <li>按渠道替换目标 URL 与鉴权头(Authorization: Bearer {真实厂商 Key})</li>
 *   <li>将上游响应(含 SSE 流)原封不动刷回下游客户端</li>
 * </ol>
 *
 * <p>SSE 流式要点:
 * <ul>
 *   <li>以 InputStream 逐 chunk 读取, 每读到一个 chunk 立即 write + flush, 绝不整包缓冲后回写 —— 这是"流式不阻塞"的关键</li>
 *   <li>请求处理运行在虚拟线程上(spring.threads.virtual.enabled=true), 阻塞读/写成本极低, 无需反应式栈</li>
 *   <li>客户端断开/上游中断 → 立即 call.cancel() 归还连接, 防止连接泄漏占满连接池</li>
 * </ul>
 *
 * <p>多渠道容灾: route 返回按优先级+轮询排序的渠道列表, 本次请求从列表头开始;
 * 连接失败或上游 5xx/429(未开始回写前)自动切换下一个渠道。
 */
@Slf4j
@Service
public class ProxyService {

    private final OkHttpClient relayClient;
    private final OkHttpClient streamRelayClient;
    private final ChannelConcurrencyService channelConcurrencyService;
    private final ChannelCircuitBreakerService channelCircuitBreakerService;
    private final UpstreamProtocolAdapter upstreamProtocolAdapter;
    private final ObjectMapper objectMapper;
    private final GatewayRuntimeSettingsService settingsService;

    /** 请求侧 hop-by-hop 头(逐跳头), 不能透传给上游 */
    private static final Set<String> STRIP_REQUEST_HEADERS = Set.of(
            "host", "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "content-length", "accept-encoding");

    /** 响应侧 hop-by-hop 头(逐跳头), 不能透传给下游 */
    private static final Set<String> STRIP_RESPONSE_HEADERS = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "content-length", "content-encoding");

    public ProxyService(@Qualifier("relayClient") OkHttpClient relayClient,
                        @Qualifier("streamRelayClient") OkHttpClient streamRelayClient,
                        ChannelConcurrencyService channelConcurrencyService,
                        ChannelCircuitBreakerService channelCircuitBreakerService,
                        UpstreamProtocolAdapter upstreamProtocolAdapter,
                        ObjectMapper objectMapper,
                         GatewayRuntimeSettingsService settingsService) {
        this.relayClient = relayClient;
        this.streamRelayClient = streamRelayClient;
        this.channelConcurrencyService = channelConcurrencyService;
        this.channelCircuitBreakerService = channelCircuitBreakerService;
        this.upstreamProtocolAdapter = upstreamProtocolAdapter;
        this.objectMapper = objectMapper;
        this.settingsService = settingsService;
    }

    /**
     * 透传(含多渠道容灾): 依次尝试候选渠道, 直到成功或所有渠道耗尽。
     */
    public void relayWithFailover(HttpServletRequest request, HttpServletResponse response,
                                  byte[] clientBody, boolean stream, List<ChannelRoute> channels)
            throws IOException {
        for (int i = 0; i < channels.size(); i++) {
            ChannelRoute channel = channels.get(i);
            boolean isLast = (i == channels.size() - 1);
            ChannelConcurrencyService.Permit permit = channelConcurrencyService.tryAcquire(channel);
            if (permit == null) {
                log.debug("[relay] channel '{}' reached max concurrent {}, failover{}",
                        channel.getChannelName(), channel.getMaxConcurrent(), isLast ? " exhausted" : " to next");
                if (isLast && !response.isCommitted()) {
                    ErrorResponses.write(response, 503, "upstream_error", "channel_concurrency_exhausted",
                            "All candidate channels are at their concurrent request limit");
                }
                continue;
            }
            try (permit) {
                request.setAttribute("ainexus.audit.targetChannel", channel.getChannelName());
                request.setAttribute("ainexus.audit.provider", channel.getProvider());
                request.setAttribute("ainexus.audit.upstreamAccount", channel.getChannelName());
                request.setAttribute("ainexus.audit.upstreamModel", channel.getUpstreamModelName());
                request.setAttribute("ainexus.audit.upstreamEndpoint", channel.getBaseUrl());
                relayOnce(request, response, clientBody, stream, channel, isLast);
                return; // 转发成功(响应已完整写回客户端)
            } catch (RetryableUpstreamException e) {
                channelCircuitBreakerService.recordFailure(channel, e.getMessage());
                log.warn("[relay] channel '{}' unavailable/retryable ({}{}), failover{}",
                        channel.getChannelName(), e.getMessage(),
                        e.statusCode > 0 ? " HTTP " + e.statusCode : "",
                        isLast ? " exhausted" : " to next");
                if (isLast && !response.isCommitted()) {
                    ErrorResponses.write(response, 502, "upstream_error", "all_channels_failed",
                            "All upstream channels failed for model: " + channel.getModelName());
                }
            } catch (IOException e) {
                // 流式中途断开/写回失败: 响应此时必然已开始提交, 无法再切渠道, 只能终止
                channelCircuitBreakerService.recordFailure(channel, e.getMessage());
                log.warn("[relay] channel '{}' interrupted: {}", channel.getChannelName(), e.toString());
                throw e;
            }
        }
    }

    /**
     * 单渠道透传。
     *
     * @param isLastCandidate 是否最后一个候选渠道(最后一个渠道不再做 5xx/429 重试, 将错误原样透传给客户端)
     */
    private void relayOnce(HttpServletRequest request, HttpServletResponse response,
                           byte[] clientBody, boolean stream, ChannelRoute channel, boolean isLastCandidate)
            throws IOException {
        long upstreamStartedAt = System.nanoTime();
        UpstreamProtocolAdapter.AdaptedRequest adapted = upstreamProtocolAdapter.adaptRequest(
                rewriteModelIfNeeded(clientBody, channel), stream, channel);
        Request upstreamRequest = buildUpstreamRequest(request, adapted, stream, channel);
        request.setAttribute("ainexus.audit.protocolPath", upstreamRequest.url().encodedPath());
        GatewayRuntimeSettingsService.Settings settings = settingsService.get();
        OkHttpClient client = stream
                ? streamRelayClient.newBuilder().readTimeout(settings.streamIdleTimeoutSeconds() > 0 ? settings.streamIdleTimeoutSeconds() : 0, TimeUnit.SECONDS).build()
                : relayClient.newBuilder().readTimeout(settings.nonStreamTimeoutSeconds(), TimeUnit.SECONDS).callTimeout(settings.nonStreamTimeoutSeconds(), TimeUnit.SECONDS).build();
        Call call = client.newCall(upstreamRequest);

        Response upstream;
        try {
            upstream = call.execute();
        } catch (IOException e) {
            throw new RetryableUpstreamException("connect fail: " + e.getMessage(), -1);
        }

        // 上游已响应、但尚未向下游写任何字节: 若状态可重试且不是最后渠道, 放弃本次尝试走容灾
        int code = upstream.code();
        request.setAttribute("ainexus.audit.upstreamStatusCode", code);
        request.setAttribute("ainexus.audit.firstTokenMs", (System.nanoTime() - upstreamStartedAt) / 1_000_000);
        if (isRetryableStatus(code)) {
            if (!isLastCandidate) {
                String snippet = drainSnippet(upstream, 512);
                upstream.close();
                throw new RetryableUpstreamException(
                        snippet.isEmpty() ? "HTTP " + code : "HTTP " + code + " body=" + snippet, code);
            }
            // 最后候选的错误需原样回传，但仍计入熔断。
            channelCircuitBreakerService.recordFailure(channel, "HTTP " + code);
        }

        // 从这里开始响应已进入"已提交区", 任何异常都无法再切换渠道
        try (Response ignored = upstream) {
            response.setStatus(upstream.code());
            ChannelProtocol protocol = ChannelProtocol.parse(channel.getProtocol());
            ResponseBody body = upstream.body();
            if (body == null) {
                return;
            }
            if (protocol != ChannelProtocol.OPENAI_COMPLETIONS && !stream) {
                // Responses/Anthropic 协议响应结构不同，完整读取后归一化为 OpenAI Chat Completions JSON。
                byte[] converted = upstreamProtocolAdapter.adaptResponse(body.bytes(), code, channel);
                response.setContentType("application/json;charset=UTF-8");
                response.getOutputStream().write(converted);
                response.getOutputStream().flush();
                if (!isRetryableStatus(code)) {
                    channelCircuitBreakerService.recordSuccess(channel);
                }
                return;
            }
            copyResponseHeaders(upstream, response);
            if (!stream) {
                byte[] responseBytes = body.bytes();
                recordUsage(request, responseBytes);
                response.getOutputStream().write(responseBytes);
                response.getOutputStream().flush();
                if (!isRetryableStatus(code)) channelCircuitBreakerService.recordSuccess(channel);
                request.setAttribute("ainexus.audit.upstreamLatencyMs", (System.nanoTime() - upstreamStartedAt) / 1_000_000);
                return;
            }
            if (stream) {
                // 关键: 告知中间代理(如 Nginx)不要缓冲 SSE, 逐字节直达客户端
                response.setHeader("X-Accel-Buffering", "no");
                response.setHeader("Cache-Control", "no-cache");
            }
            try (InputStream in = body.byteStream();
                 OutputStream out = response.getOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    out.flush(); // 每个 chunk 立即刷回下游 — SSE 实时性的核心
                }
                out.flush();
                if (!isRetryableStatus(code)) {
                    channelCircuitBreakerService.recordSuccess(channel);
                }
                request.setAttribute("ainexus.audit.upstreamLatencyMs", (System.nanoTime() - upstreamStartedAt) / 1_000_000);
            } catch (IOException e) {
                // 客户端断开或上游中断: 取消请求, 立刻归还底层连接, 防止连接泄漏
                call.cancel();
                throw e;
            }
        }
    }

    /**
     * 构造上游请求: 原样透传客户端报文, 仅替换 Base URL 与鉴权头。
     */
    private Request buildUpstreamRequest(HttpServletRequest request, UpstreamProtocolAdapter.AdaptedRequest adapted,
                                         boolean stream, ChannelRoute channel) {
        String baseUrl = channel.getBaseUrl().replaceAll("/+$", "");
        String path = channel.getRequestPath() == null || channel.getRequestPath().isBlank()
                ? adapted.defaultPath()
                : channel.getRequestPath();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        String query = request.getQueryString();
        String url = baseUrl + path + (query == null || query.isBlank() ? "" : "?" + query);

        Headers.Builder headers = new Headers.Builder();
        java.util.Enumeration<String> names = request.getHeaderNames();
        if (names != null) {
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                if (STRIP_REQUEST_HEADERS.contains(name.toLowerCase())) {
                    continue;
                }
                headers.add(name, request.getHeader(name));
            }
        }
        // 协议默认鉴权可由 protocol_config 覆盖；Anthropic 使用 x-api-key + anthropic-version。
        String authHeader = channel.getAuthHeader();
        String authPrefix = channel.getAuthPrefix();
        if (adapted.authHeader() != null) {
            authHeader = adapted.authHeader();
            authPrefix = adapted.authPrefix();
        }
        headers.removeAll("Authorization");
        headers.removeAll("x-api-key");
        headers.removeAll(authHeader);
        headers.set(authHeader, authPrefix + channel.getApiKey());
        if (adapted.anthropicVersion() != null) {
            headers.set("anthropic-version", adapted.anthropicVersion());
        }
        channel.getExtraHeaders().forEach(headers::set);
        if (stream) {
            headers.set("Accept", "text/event-stream");
        }

        MediaType mediaType = MediaType.parse(request.getContentType() != null
                ? request.getContentType() : "application/json");
        RequestBody body = RequestBody.create(adapted.body(), mediaType);
        return new Request.Builder()
                .url(url)
                .headers(headers.build())
                .post(body)
                .build();
    }

    private void recordUsage(HttpServletRequest request, byte[] responseBytes) {
        try {
            JsonNode root = objectMapper.readTree(responseBytes);
            JsonNode usage = root.path("usage");
            if (!usage.isObject()) return;
            long input = usage.path("prompt_tokens").asLong(usage.path("input_tokens").asLong(0));
            long output = usage.path("completion_tokens").asLong(usage.path("output_tokens").asLong(0));
            long total = usage.path("total_tokens").asLong(input + output);
            JsonNode details = usage.path("prompt_tokens_details");
            long cached = details.path("cached_tokens").asLong(usage.path("cache_read_input_tokens").asLong(0));
            request.setAttribute("ainexus.audit.inputTokens", input);
            request.setAttribute("ainexus.audit.outputTokens", output);
            request.setAttribute("ainexus.audit.totalTokens", total);
            request.setAttribute("ainexus.audit.cacheTokens", cached);
        } catch (Exception e) {
            log.debug("[audit] response usage parsing skipped: {}", e.getMessage());
        }
    }

    /**
     * upstream_model_name 存在且与客户端 model 不同时，仅重写 model 字段；其他 JSON 字节和字段保持原样。
     * 不配置映射时直接返回原始 byte[]，维持严格透明透传。
     */
    private byte[] rewriteModelIfNeeded(byte[] clientBody, ChannelRoute channel) throws IOException {
        String upstreamModel = channel.getUpstreamModelName();
        if (upstreamModel == null || upstreamModel.isBlank() || upstreamModel.equals(channel.getModelName())) {
            return clientBody;
        }
        JsonNode root = objectMapper.readTree(clientBody);
        if (!root.isObject()) {
            return clientBody;
        }
        ((ObjectNode) root).put("model", upstreamModel);
        return objectMapper.writeValueAsBytes(root);
    }

    private void copyResponseHeaders(Response upstream, HttpServletResponse response) {
        Headers headers = upstream.headers();
        for (int i = 0; i < headers.size(); i++) {
            String name = headers.name(i);
            if (STRIP_RESPONSE_HEADERS.contains(name.toLowerCase())) {
                continue; // OkHttp 已透明解压 gzip, content-encoding/content-length 原样透传无意义
            }
            response.addHeader(name, headers.value(i));
        }
    }

    private boolean isRetryableStatus(int code) {
        return code >= 500 || code == 429;
    }

    /** 读取上游响应体前 maxBytes 字节用于日志(仅在容灾切换前调用) */
    private String drainSnippet(Response response, int maxBytes) {
        try {
            ResponseBody body = response.body();
            if (body == null) {
                return "";
            }
            byte[] buf = new byte[maxBytes];
            try (InputStream in = body.byteStream()) {
                int n = in.read(buf);
                return n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8) : "";
            }
        } catch (Exception e) {
            return "";
        }
    }

    /** 可重试的上游异常(连接失败 / 5xx / 429, 且发生在向下游写任何字节之前) */
    private static class RetryableUpstreamException extends IOException {
        final int statusCode;

        RetryableUpstreamException(String message, int statusCode) {
            super(message);
            this.statusCode = statusCode;
        }
    }
}