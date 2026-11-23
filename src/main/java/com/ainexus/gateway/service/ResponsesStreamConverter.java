package com.ainexus.gateway.service;

import com.ainexus.gateway.route.ChannelRoute;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/** Converts Responses API events into OpenAI Chat Completions chunks without buffering the whole response. */
final class ResponsesStreamConverter {
    private static final int MAX_LINE_BYTES = 256 * 1024;
    private final ObjectMapper mapper;
    private final ChannelRoute route;
    private final OutputStream output;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream(1024);
    private final StringBuilder data = new StringBuilder();
    private String eventType;
    private int eventBytes;
    private String id = "chatcmpl-ainexus";
    private long created = Instant.now().getEpochSecond();
    private boolean roleSent;
    private boolean finished;
    private boolean toolCalls;
    private UsageMetrics usage;

    ResponsesStreamConverter(ObjectMapper mapper, ChannelRoute route, OutputStream output) {
        this.mapper = mapper;
        this.route = route;
        this.output = output;
    }

    UsageMetrics relay(InputStream input) throws IOException {
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            for (int i = 0; i < count; i++) {
                if (buffer[i] == '\n') {
                    processLine();
                    line.reset();
                } else if (line.size() < MAX_LINE_BYTES) {
                    line.write(buffer[i]);
                } else {
                    throw new IOException("Responses SSE line exceeds maximum length");
                }
            }
        }
        if (line.size() > 0) processLine();
        if (data.length() > 0) dispatch();
        if (!finished) throw new IOException("Responses SSE ended before response.completed");
        return usage;
    }

    private void processLine() throws IOException {
        String text = line.toString(StandardCharsets.UTF_8);
        if (text.endsWith("\r")) text = text.substring(0, text.length() - 1);
        if (text.isEmpty()) {
            dispatch();
            eventBytes = 0;
        } else if (text.startsWith("event:")) {
            eventType = text.substring(6).trim();
        } else if (text.startsWith("data:")) {
            String content = text.substring(5).stripLeading();
            eventBytes += content.getBytes(StandardCharsets.UTF_8).length;
            if (eventBytes > MAX_LINE_BYTES) throw new IOException("Responses SSE event exceeds maximum length");
            if (data.length() > 0) data.append('\n');
            data.append(content);
        }
    }

    private void dispatch() throws IOException {
        if (data.length() == 0) {
            eventType = null;
            return;
        }
        String payload = data.toString();
        data.setLength(0);
        eventBytes = 0;
        String type = eventType;
        eventType = null;
        if ("[DONE]".equals(payload)) return;
        if (finished) return;
        JsonNode event = mapper.readTree(payload);
        if (event == null || !event.isObject()) return;
        if (event.hasNonNull("type")) type = event.path("type").asText();
        if (type == null) return;
        switch (type) {
            case "response.created", "response.in_progress" -> {
                JsonNode response = event.path("response");
                if (response.hasNonNull("id")) id = response.path("id").asText();
                if (response.has("created_at")) created = response.path("created_at").asLong(created);
                sendRole();
            }
            case "response.output_text.delta", "response.refusal.delta" -> {
                String delta = event.path("delta").asText("");
                if (!delta.isEmpty()) {
                    sendRole();
                    ObjectNode chunk = chunk();
                    chunk.withArray("choices").addObject().put("index", 0).putObject("delta").put("content", delta);
                    write(chunk);
                }
            }
            case "response.output_item.added" -> {
                JsonNode item = event.path("item");
                if ("function_call".equals(item.path("type").asText())) {
                    sendRole();
                    toolCalls = true;
                    ObjectNode chunk = chunk();
                    ObjectNode delta = chunk.withArray("choices").addObject().put("index", 0).putObject("delta");
                    ObjectNode call = delta.putArray("tool_calls").addObject();
                    call.put("index", event.path("output_index").asInt());
                    call.put("id", item.path("call_id").asText(item.path("id").asText("")));
                    call.put("type", "function");
                    ObjectNode function = call.putObject("function");
                    function.put("name", item.path("name").asText(""));
                    function.put("arguments", "");
                    write(chunk);
                }
            }
            case "response.function_call_arguments.delta" -> {
                sendRole();
                toolCalls = true;
                ObjectNode chunk = chunk();
                ObjectNode call = chunk.withArray("choices").addObject().put("index", 0)
                        .putObject("delta").putArray("tool_calls").addObject();
                call.put("index", event.path("output_index").asInt());
                call.putObject("function").put("arguments", event.path("delta").asText(""));
                write(chunk);
            }
            case "response.completed" -> {
                JsonNode response = event.path("response");
                if (response.hasNonNull("id")) id = response.path("id").asText();
                if (!"completed".equals(response.path("status").asText("completed"))) {
                    throw new IOException("Responses SSE ended with status " + response.path("status").asText());
                }
                UsageMetrics parsed = UsageMetrics.fromUsage(response.path("usage"));
                if (parsed != null) usage = parsed;
                finish(toolCalls ? "tool_calls" : "stop");
            }
            case "response.incomplete", "response.failed", "error" -> {
                JsonNode response = event.path("response");
                ObjectNode error = mapper.createObjectNode();
                JsonNode details = response.path("error");
                if (details.isMissingNode() || details.isNull()) details = event.path("error");
                if (details.isMissingNode() || details.isNull()) details = event;
                error.set("error", details);
                write(error);
                throw new IOException("Responses SSE terminated with " + type);
            }
            default -> { /* Ignore metadata events, not Chat Completions deltas. */ }
        }
    }

    private void sendRole() throws IOException {
        if (roleSent || finished) return;
        roleSent = true;
        ObjectNode chunk = chunk();
        chunk.withArray("choices").addObject().put("index", 0).putObject("delta").put("role", "assistant");
        write(chunk);
    }

    private void finish(String reason) throws IOException {
        if (finished) return;
        sendRole();
        finished = true;
        ObjectNode chunk = chunk();
        ObjectNode choice = chunk.withArray("choices").addObject();
        choice.put("index", 0);
        choice.set("delta", mapper.createObjectNode());
        choice.put("finish_reason", reason);
        write(chunk);
        if (usage != null) {
            ObjectNode summary = chunk();
            summary.set("choices", mapper.createArrayNode());
            ObjectNode tokens = summary.putObject("usage");
            long input = usage.inputTokens() == null ? 0 : usage.inputTokens();
            long cache = usage.cacheReadTokens() == null ? 0 : usage.cacheReadTokens();
            long outputTokens = usage.outputTokens() == null ? 0 : usage.outputTokens();
            tokens.put("prompt_tokens", input + cache);
            tokens.put("completion_tokens", outputTokens);
            tokens.put("total_tokens", input + cache + outputTokens);
            if (usage.cacheReadTokens() != null) tokens.putObject("prompt_tokens_details").put("cached_tokens", cache);
            write(summary);
        }
        output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private ObjectNode chunk() {
        ObjectNode chunk = mapper.createObjectNode();
        chunk.put("id", id);
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", created);
        chunk.put("model", route.getModelName());
        return chunk;
    }

    private void write(ObjectNode chunk) throws IOException {
        output.write("data: ".getBytes(StandardCharsets.UTF_8));
        output.write(mapper.writeValueAsBytes(chunk));
        output.write("\n\n".getBytes(StandardCharsets.UTF_8));
        output.flush();
    }
}
