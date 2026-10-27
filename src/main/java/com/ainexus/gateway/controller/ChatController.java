package com.ainexus.gateway.controller;

import com.ainexus.gateway.common.BizException;
import com.ainexus.gateway.route.ChannelRoute;
import com.ainexus.gateway.service.AuditLogService;
import com.ainexus.gateway.service.ChannelService;
import com.ainexus.gateway.service.ProxyService;
import com.ainexus.gateway.service.RouteCacheService;
import com.ainexus.gateway.service.ModelAuthorizationService;
import com.ainexus.gateway.service.AuditDimensionService;
import com.ainexus.gateway.service.GatewayConcurrencyService;
import com.ainexus.gateway.service.GatewayRuntimeSettingsService;
import com.ainexus.gateway.service.UserGroupConcurrencyService;
import com.ainexus.gateway.service.GatewayActivityService;
import com.ainexus.gateway.service.UserGroupCallWindowService;
import com.ainexus.gateway.service.UserGroupTokenQuotaService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;

/** OpenAI 兼容统一入口。 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ChatController {

    private final ChannelService channelService;
    private final RouteCacheService routeCacheService;
    private final ProxyService proxyService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final ModelAuthorizationService modelAuthorizationService;
    private final AuditDimensionService auditDimensionService;
    private final GatewayConcurrencyService gatewayConcurrencyService;
    private final GatewayRuntimeSettingsService settingsService;
    private final UserGroupConcurrencyService userGroupConcurrencyService;
    private final GatewayActivityService gatewayActivityService;
    private final UserGroupTokenQuotaService userGroupTokenQuotaService;
    private final UserGroupCallWindowService userGroupCallWindowService;

    /** OpenAI-compatible local model catalog generated from the current routing cache. */
    @GetMapping("/v1/models")
    public ModelListResponse models(HttpServletRequest request) {
        return new ModelListResponse("list", availableModels(request).stream()
                .map(model -> new ModelItem(model, "model", "ainexus", 0L))
                .toList());
    }

    @PostMapping("/v1/chat/completions")
    public void chatCompletions(HttpServletRequest request, HttpServletResponse response,
                                @RequestBody(required = false) byte[] body) throws IOException {
        relay(request, response, body);
    }

    @PostMapping("/v1/**")
    public void relayOthers(HttpServletRequest request, HttpServletResponse response,
                            @RequestBody(required = false) byte[] body) throws IOException {
        relay(request, response, body);
    }

    private void relay(HttpServletRequest request, HttpServletResponse response, byte[] body) throws IOException {
        long startedAt = System.nanoTime();
        String requestId = java.util.UUID.randomUUID().toString();
        String model = null;
        Integer promptTokens = null;
        Throwable failure = null;
        try {
            if (body == null || body.length == 0) {
                throw BizException.badRequest("empty_body", "Request body must not be empty");
            }
            long maxBytes = (long) settingsService.get().maxPayloadMb() * 1024 * 1024;
            if (body.length > maxBytes) throw BizException.badRequest("payload_too_large", "Request body exceeds configured maximum payload size");
            body = stripUtf8Bom(body);
            JsonNode root = objectMapper.readTree(body);
            JsonNode modelNode = root.get("model");
            if (modelNode == null || modelNode.asText().isBlank()) {
                throw BizException.badRequest("missing_model", "Field 'model' is required");
            }
            model = modelNode.asText().trim();
            if (!availableModels(request).contains(model)) {
                throw BizException.forbidden("model_not_authorized", "Model is not authorized for this API key");
            }
            promptTokens = estimatePromptTokens(root);
            boolean stream = root.path("stream").asBoolean(false);
            request.setAttribute("ainexus.audit.stream", stream);

            userGroupCallWindowService.check((Long) request.getAttribute("ainexus.auth.accountId"));
            userGroupTokenQuotaService.check((Long) request.getAttribute("ainexus.auth.accountId"));
            List<ChannelRoute> channels = channelService.route(model, cacheAffinityKey(model, root));
            log.info("[relay] model={} stream={} candidates={}", model, stream,
                    channels.stream().map(ChannelRoute::getChannelName).toList());
            long queueStartedAt = System.nanoTime();
            try (GatewayConcurrencyService.Permit ignored = gatewayConcurrencyService.acquire();
                 UserGroupConcurrencyService.Permit groupPermit = userGroupConcurrencyService.acquire((Long) request.getAttribute("ainexus.auth.accountId"));
                 GatewayActivityService.Permit activityPermit = gatewayActivityService.begin((Long) request.getAttribute("ainexus.auth.accountId"), stream)) {
                request.setAttribute("ainexus.audit.queueMs", (System.nanoTime() - queueStartedAt) / 1_000_000);
                proxyService.relayWithFailover(request, response, body, stream, channels);
            }
        } catch (IOException | RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;
            Long accountId = (Long) request.getAttribute("ainexus.auth.accountId");
            AuditDimensionService.Dimensions dimensions = auditDimensionService.resolve(accountId, model);
            Long inputTokens = (Long) request.getAttribute("ainexus.audit.inputTokens");
            String appName = (String) request.getAttribute("ainexus.auth.appName");
            String upstreamModel = (String) request.getAttribute("ainexus.audit.upstreamModel");
            auditLogService.submit(new AuditLogService.AuditEvent(
                    requestId, appName, appName, dimensions.accountId(), dimensions.userName(), model,
                    upstreamModel == null ? model : upstreamModel, (String) request.getAttribute("ainexus.audit.targetChannel"),
                    (String) request.getAttribute("ainexus.audit.provider"), (String) request.getAttribute("ainexus.audit.upstreamAccount"),
                    apiType(request), Boolean.TRUE.equals(request.getAttribute("ainexus.audit.stream")),
                    (String) request.getAttribute("ainexus.audit.upstreamEndpoint"), (String) request.getAttribute("ainexus.audit.protocolPath"),
                    dimensions.modelGroupNames(), dimensions.userGroupNames(), inputTokens == null ? (promptTokens == null ? null : promptTokens.longValue()) : inputTokens,
                    (Long) request.getAttribute("ainexus.audit.outputTokens"), (Long) request.getAttribute("ainexus.audit.totalTokens"),
                    (Long) request.getAttribute("ainexus.audit.cacheTokens"), resolveStatus(response, failure),
                    (Integer) request.getAttribute("ainexus.audit.upstreamStatusCode"), latencyMs,
                    (Long) request.getAttribute("ainexus.audit.firstTokenMs"), (Long) request.getAttribute("ainexus.audit.queueMs"), (Long) request.getAttribute("ainexus.audit.upstreamLatencyMs"),
                    failure instanceof BizException biz ? biz.getCode() : failure == null ? null : "gateway_error",
                    failure == null ? null : failure.getMessage()));
        }
    }

    private String apiType(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.endsWith("/chat/completions")) return "chat_completions";
        if (path.endsWith("/responses")) return "responses";
        return "openai_compatible";
    }

    private List<String> availableModels(HttpServletRequest request) {
        Long accountId = (Long) request.getAttribute("ainexus.auth.accountId");
        if (accountId == null) return routeCacheService.getAvailableModels();
        java.util.Set<String> authorized = modelAuthorizationService.authorizedModels(accountId);
        return routeCacheService.getAvailableModels().stream().filter(authorized::contains).toList();
    }

    /** 兼容 Windows PowerShell Out-File 等工具产生的 UTF-8 BOM；仅发现 BOM 时复制并剥离前三字节。 */
    private byte[] stripUtf8Bom(byte[] body) {
        if (body.length >= 3 && body[0] == (byte) 0xEF && body[1] == (byte) 0xBB && body[2] == (byte) 0xBF) {
            return Arrays.copyOfRange(body, 3, body.length);
        }
        return body;
    }

    /**
     * Creates a stable cache-affinity key from the immutable beginning of a conversation.
     * New turns append messages, so using the system prompt and first user message keeps the
     * same upstream account selected while provider-side prompt caches remain reusable.
     */
    private String cacheAffinityKey(String model, JsonNode root) {
        StringBuilder source = new StringBuilder(model).append('\n');
        for (JsonNode message : root.withArray("messages")) {
            String role = message.path("role").asText();
            if (!"system".equals(role) && !"developer".equals(role) && !"user".equals(role)) {
                continue;
            }
            JsonNode content = message.path("content");
            if (!content.isTextual()) {
                continue;
            }
            source.append(role).append(':').append(content.asText()).append('\n');
            if ("user".equals(role)) {
                break;
            }
        }
        if (source.length() == model.length() + 1) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                key.append(String.format("%02x", value));
            }
            return key.toString();
        } catch (Exception ignored) {
            return source.toString();
        }
    }

    /** 仅作审计近似值：字符串 content 总字符数 / 4；复杂多模态消息返回 null。 */
    private Integer estimatePromptTokens(JsonNode root) {
        JsonNode messages = root.path("messages");
        if (!messages.isArray()) {
            return null;
        }
        int characters = 0;
        for (JsonNode message : messages) {
            JsonNode content = message.get("content");
            if (content == null || content.isNull()) {
                continue;
            }
            if (!content.isTextual()) {
                return null;
            }
            characters += content.asText().length();
        }
        return (characters + 3) / 4;
    }

    /** OpenAI GET /v1/models 响应结构。 */
    public record ModelListResponse(String object, List<ModelItem> data) { }
    public record ModelItem(String id, String object, String ownedBy, long created) { }

    private int resolveStatus(HttpServletResponse response, Throwable failure) {
        // 异常由 @RestControllerAdvice 在本方法返回后统一渲染，此时 Servlet 默认 status 仍可能是 200。
        if (failure instanceof BizException biz) {
            return biz.getStatus();
        }
        if (failure instanceof JsonProcessingException) {
            return 400;
        }
        if (failure != null) {
            return response.isCommitted() ? response.getStatus() : 502;
        }
        return response.getStatus() > 0 ? response.getStatus() : 200;
    }
}
