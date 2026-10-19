package com.ainexus.gateway.service;

import com.ainexus.gateway.common.BizException;
import com.ainexus.gateway.route.ChannelProtocol;
import com.ainexus.gateway.route.ChannelRoute;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;

/**
 * OpenAI Chat Completions 客户端协议到各上游协议的最小适配层。
 *
 * <p>只在 route.protocol 不是 openai-completions 时重建 JSON；标准 OpenAI 渠道继续原始 byte[] 透传。
 * 目前 Responses / Anthropic Messages 支持非流式请求和统一的 Chat Completions 非流式响应。
 */
@Component
@RequiredArgsConstructor
public class UpstreamProtocolAdapter {

    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final ObjectMapper objectMapper;

    public AdaptedRequest adaptRequest(byte[] openAiBody, boolean stream, ChannelRoute route) throws IOException {
        ChannelProtocol protocol = ChannelProtocol.parse(route.getProtocol());
        if (stream && protocol != ChannelProtocol.OPENAI_COMPLETIONS) {
            throw BizException.unsupportedProtocolStream(protocol.value());
        }
        if (protocol == ChannelProtocol.OPENAI_COMPLETIONS) {
            return new AdaptedRequest(openAiBody, defaultPath(protocol), "Authorization", "Bearer ", null);
        }

        JsonNode root = objectMapper.readTree(openAiBody);
        if (!root.isObject()) {
            return new AdaptedRequest(openAiBody, defaultPath(protocol), null, null, null);
        }
        ObjectNode request = (ObjectNode) root;
        String upstreamModel = route.getUpstreamModelName();
        if (upstreamModel != null && !upstreamModel.isBlank()) {
            request.put("model", upstreamModel);
        }
        return switch (protocol) {
            case OPENAI_RESPONSES -> toResponses(request);
            case ANTHROPIC_MESSAGES -> toAnthropic(request);
            case OPENAI_COMPLETIONS -> throw new IllegalStateException("unreachable");
        };
    }

    public byte[] adaptResponse(byte[] upstreamBody, int upstreamStatus, ChannelRoute route) throws IOException {
        if (upstreamStatus >= 400) {
            return upstreamBody;
        }
        return switch (ChannelProtocol.parse(route.getProtocol())) {
            case OPENAI_COMPLETIONS -> upstreamBody;
            case OPENAI_RESPONSES -> responsesToChatCompletion(upstreamBody, route);
            case ANTHROPIC_MESSAGES -> anthropicToChatCompletion(upstreamBody, route);
        };
    }

    public String defaultPath(ChannelProtocol protocol) {
        return switch (protocol) {
            case OPENAI_COMPLETIONS -> "/v1/chat/completions";
            case OPENAI_RESPONSES -> "/v1/responses";
            case ANTHROPIC_MESSAGES -> "/v1/messages";
        };
    }

    private AdaptedRequest toResponses(ObjectNode source) throws IOException {
        ObjectNode target = objectMapper.createObjectNode();
        target.set("model", source.get("model"));
        ArrayNode input = target.putArray("input");
        JsonNode messages = source.path("messages");
        if (messages.isArray()) {
            for (JsonNode message : messages) {
                ObjectNode item = input.addObject();
                item.put("role", message.path("role").asText("user"));
                JsonNode content = message.path("content");
                if (content.isTextual()) {
                    item.put("content", content.asText());
                } else if (!content.isMissingNode()) {
                    item.set("content", content);
                }
            }
        }
        copyIfPresent(source, target, "temperature", "top_p", "max_output_tokens", "max_tokens", "metadata");
        if (source.has("max_tokens") && !target.has("max_output_tokens")) {
            target.set("max_output_tokens", source.get("max_tokens"));
        }
        return new AdaptedRequest(write(target), defaultPath(ChannelProtocol.OPENAI_RESPONSES), "Authorization", "Bearer ", null);
    }

    private AdaptedRequest toAnthropic(ObjectNode source) throws IOException {
        ObjectNode target = objectMapper.createObjectNode();
        target.set("model", source.get("model"));
        target.put("max_tokens", source.path("max_tokens").asInt(source.path("max_completion_tokens").asInt(1024)));
        copyIfPresent(source, target, "temperature", "top_p", "top_k", "stop_sequences");

        ArrayNode messages = target.putArray("messages");
        ArrayNode sourceMessages = source.withArray("messages");
        for (JsonNode message : sourceMessages) {
            String role = message.path("role").asText("user");
            if ("system".equals(role)) {
                appendSystem(target, message.path("content"));
                continue;
            }
            ObjectNode targetMessage = messages.addObject();
            targetMessage.put("role", "assistant".equals(role) ? "assistant" : "user");
            JsonNode content = message.path("content");
            if (content.isTextual()) {
                targetMessage.put("content", content.asText());
            } else if (!content.isMissingNode()) {
                targetMessage.set("content", content);
            }
        }
        return new AdaptedRequest(write(target), defaultPath(ChannelProtocol.ANTHROPIC_MESSAGES), "x-api-key", "", ANTHROPIC_VERSION);
    }

    private void appendSystem(ObjectNode target, JsonNode content) {
        if (content.isTextual()) {
            target.put("system", content.asText());
        } else if (!content.isMissingNode()) {
            target.set("system", content);
        }
    }

    private byte[] responsesToChatCompletion(byte[] body, ChannelRoute route) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        String content = root.path("output_text").asText(null);
        if (content == null) {
            StringBuilder result = new StringBuilder();
            for (JsonNode output : root.withArray("output")) {
                for (JsonNode item : output.withArray("content")) {
                    if ("output_text".equals(item.path("type").asText())) {
                        result.append(item.path("text").asText());
                    }
                }
            }
            content = result.toString();
        }
        ObjectNode converted = chatCompletionEnvelope(route, root.path("id").asText(null), content);
        JsonNode usage = root.path("usage");
        if (usage.isObject()) {
            ObjectNode convertedUsage = converted.putObject("usage");
            convertedUsage.put("prompt_tokens", usage.path("input_tokens").asInt(0));
            convertedUsage.put("completion_tokens", usage.path("output_tokens").asInt(0));
            convertedUsage.put("total_tokens", usage.path("total_tokens").asInt(
                    usage.path("input_tokens").asInt(0) + usage.path("output_tokens").asInt(0)));
        }
        return write(converted);
    }

    private byte[] anthropicToChatCompletion(byte[] body, ChannelRoute route) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        StringBuilder content = new StringBuilder();
        for (JsonNode item : root.withArray("content")) {
            if ("text".equals(item.path("type").asText())) {
                content.append(item.path("text").asText());
            }
        }
        ObjectNode converted = chatCompletionEnvelope(route, root.path("id").asText(null), content.toString());
        JsonNode usage = root.path("usage");
        if (usage.isObject()) {
            ObjectNode convertedUsage = converted.putObject("usage");
            convertedUsage.put("prompt_tokens", usage.path("input_tokens").asInt(0));
            convertedUsage.put("completion_tokens", usage.path("output_tokens").asInt(0));
            convertedUsage.put("total_tokens", usage.path("input_tokens").asInt(0) + usage.path("output_tokens").asInt(0));
        }
        return write(converted);
    }

    private ObjectNode chatCompletionEnvelope(ChannelRoute route, String id, String content) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("id", id == null || id.isBlank() ? "chatcmpl-ainexus" : id);
        result.put("object", "chat.completion");
        result.put("created", Instant.now().getEpochSecond());
        result.put("model", route.getModelName());
        ObjectNode choice = result.putArray("choices").addObject();
        choice.put("index", 0);
        choice.putObject("message").put("role", "assistant").put("content", content == null ? "" : content);
        choice.put("finish_reason", "stop");
        return result;
    }

    private void copyIfPresent(ObjectNode from, ObjectNode to, String... fields) {
        for (String field : fields) {
            if (from.has(field)) {
                to.set(field, from.get(field));
            }
        }
    }

    private byte[] write(JsonNode node) throws IOException {
        return objectMapper.writeValueAsBytes(node);
    }

    public record AdaptedRequest(byte[] body, String defaultPath, String authHeader, String authPrefix,
                                 String anthropicVersion) { }
}
