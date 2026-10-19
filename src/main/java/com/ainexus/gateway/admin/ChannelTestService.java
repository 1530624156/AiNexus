package com.ainexus.gateway.admin;

import com.ainexus.gateway.entity.Channel;
import com.ainexus.gateway.entity.ChannelModel;
import com.ainexus.gateway.route.ChannelProtocol;
import com.ainexus.gateway.route.ChannelRoute;
import com.ainexus.gateway.service.RouteCacheService;
import com.ainexus.gateway.service.UpstreamProtocolAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** 管理端渠道连通性测试，不进入生产路由、限流、熔断或审计链路。 */
@Service
@RequiredArgsConstructor
public class ChannelTestService {

    private static final int MAX_BATCH_SIZE = 20;
    private static final int MAX_PARALLEL_TESTS = 4;
    private static final int MAX_RESPONSE_PREVIEW = 500;
    private static final String DEFAULT_PROMPT = "Reply with exactly: OK";

    @Qualifier("relayClient")
    private final OkHttpClient relayClient;
    private final ObjectMapper objectMapper;
    private final RouteCacheService routeCacheService;
    private final UpstreamProtocolAdapter upstreamProtocolAdapter;

    public List<TestResult> test(Channel channel, List<ChannelModel> bindings, List<String> modelNames, String prompt) {
        Map<String, ChannelModel> bindingByName = new LinkedHashMap<>();
        bindings.forEach(binding -> bindingByName.put(binding.getModelName(), binding));
        List<String> selected = modelNames == null || modelNames.isEmpty()
                ? new ArrayList<>(bindingByName.keySet()) : modelNames.stream().distinct().toList();
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("No channel models available to test");
        }
        if (selected.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("A batch can test at most " + MAX_BATCH_SIZE + " models");
        }
        for (String name : selected) {
            if (!bindingByName.containsKey(name)) {
                throw new IllegalArgumentException("Model is not bound to this channel: " + name);
            }
        }

        String effectivePrompt = StringUtils.hasText(prompt) ? prompt.trim() : DEFAULT_PROMPT;
        Semaphore permits = new Semaphore(MAX_PARALLEL_TESTS);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<TestResult>> futures = selected.stream()
                    .map(name -> CompletableFuture.supplyAsync(() -> {
                        permits.acquireUninterruptibly();
                        try {
                            return testOne(channel, bindingByName.get(name), effectivePrompt);
                        } finally {
                            permits.release();
                        }
                    }, executor))
                    .toList();
            return futures.stream().map(CompletableFuture::join).toList();
        }
    }

    private TestResult testOne(Channel channel, ChannelModel binding, String prompt) {
        long started = System.nanoTime();
        String publicModel = binding.getModelName();
        try {
            ChannelRoute route = routeCacheService.toRoute(channel, binding);
            String upstreamModel = StringUtils.hasText(route.getUpstreamModelName()) ? route.getUpstreamModelName() : publicModel;
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", upstreamModel);
            requestBody.putArray("messages").addObject().put("role", "user").put("content", prompt);
            requestBody.put("stream", false);
            byte[] source = objectMapper.writeValueAsBytes(requestBody);
            UpstreamProtocolAdapter.AdaptedRequest adapted = upstreamProtocolAdapter.adaptRequest(source, false, route);
            Request request = buildRequest(route, adapted);

            try (Response response = relayClient.newBuilder().callTimeout(45, TimeUnit.SECONDS).build()
                    .newCall(request).execute()) {
                ResponseBody body = response.body();
                byte[] bytes = body == null ? new byte[0] : body.bytes();
                int latency = elapsedMs(started);
                if (!response.isSuccessful()) {
                    return new TestResult(publicModel, upstreamModel, false, response.code(), latency, null,
                            preview(bytes));
                }
                byte[] normalized = upstreamProtocolAdapter.adaptResponse(bytes, response.code(), route);
                return new TestResult(publicModel, upstreamModel, true, response.code(), latency,
                        extractText(normalized), null);
            }
        } catch (Exception e) {
            return new TestResult(publicModel, binding.getUpstreamModelName(), false, null, elapsedMs(started), null,
                    safeMessage(e));
        }
    }

    private Request buildRequest(ChannelRoute route, UpstreamProtocolAdapter.AdaptedRequest adapted) {
        String path = StringUtils.hasText(route.getRequestPath()) ? route.getRequestPath() : adapted.defaultPath();
        if (!path.startsWith("/")) path = "/" + path;
        String url = route.getBaseUrl().replaceAll("/+$", "") + path;
        String authHeader = adapted.authHeader() == null ? route.getAuthHeader() : adapted.authHeader();
        String authPrefix = adapted.authPrefix() == null ? route.getAuthPrefix() : adapted.authPrefix();
        Request.Builder builder = new Request.Builder().url(url)
                .post(RequestBody.create(adapted.body(), MediaType.get("application/json; charset=utf-8")))
                .header(authHeader, authPrefix + route.getApiKey());
        if (adapted.anthropicVersion() != null) builder.header("anthropic-version", adapted.anthropicVersion());
        route.getExtraHeaders().forEach(builder::header);
        return builder.build();
    }

    private String extractText(byte[] response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            return root.path("choices").path(0).path("message").path("content").asText(preview(response));
        } catch (Exception ignored) {
            return preview(response);
        }
    }

    private String preview(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();
        return text.length() <= MAX_RESPONSE_PREVIEW ? text : text.substring(0, MAX_RESPONSE_PREVIEW) + "...";
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        if (!StringUtils.hasText(message)) return e.getClass().getSimpleName();
        return message.length() <= MAX_RESPONSE_PREVIEW ? message : message.substring(0, MAX_RESPONSE_PREVIEW) + "...";
    }

    private int elapsedMs(long started) {
        return (int) Math.min(Integer.MAX_VALUE, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    public record TestResult(String modelName, String upstreamModelName, boolean success, Integer statusCode,
                             int latencyMs, String responsePreview, String errorMessage) { }
}
