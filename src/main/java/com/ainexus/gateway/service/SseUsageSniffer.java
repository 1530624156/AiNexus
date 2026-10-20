package com.ainexus.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Extracts final/cumulative usage objects from an SSE stream without buffering its response body. */
final class SseUsageSniffer {
    private static final int MAX_LINE_BYTES = 256 * 1024;

    private final ObjectMapper objectMapper;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream(1024);
    private boolean lineTooLong;
    private UsageMetrics metrics;

    SseUsageSniffer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void feed(byte[] bytes, int offset, int length) {
        for (int index = offset; index < offset + length; index++) {
            byte value = bytes[index];
            if (value == '\n') {
                inspectLine();
                line.reset();
                lineTooLong = false;
            } else if (!lineTooLong) {
                if (line.size() < MAX_LINE_BYTES) {
                    line.write(value);
                } else {
                    lineTooLong = true;
                }
            }
        }
    }

    UsageMetrics finish() {
        inspectLine();
        return metrics;
    }

    private void inspectLine() {
        if (lineTooLong || line.size() == 0) {
            return;
        }
        String value = line.toString(StandardCharsets.UTF_8).trim();
        if (!value.startsWith("data:")) {
            return;
        }
        String data = value.substring("data:".length()).trim();
        if (data.isEmpty() || "[DONE]".equals(data) || !data.contains("\"usage\"")) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(data);
            JsonNode usage = root.path("usage");
            if (!usage.isObject()) {
                usage = root.path("message").path("usage");
            }
            UsageMetrics parsed = UsageMetrics.fromUsage(usage);
            if (parsed != null) {
                metrics = metrics == null ? parsed : metrics.merge(parsed);
            }
        } catch (Exception ignored) {
            // A malformed vendor-specific event must never interrupt response streaming.
        }
    }
}
