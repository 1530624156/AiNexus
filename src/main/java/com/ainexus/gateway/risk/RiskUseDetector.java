package com.ainexus.gateway.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Classifies only clearly non-development scenarios. Results are review candidates,
 * never enforcement decisions. Review content is truncated and credential-redacted before persistence.
 */
@Component
public class RiskUseDetector {
    private static final List<Rule> RULES = List.of(
            new Rule("CHATBOT", "customer_service_bot", 45, "客服机器人|智能客服|问答机器人|知识库问答|机器人回复"),
            new Rule("AGENT_PLATFORM", "agent_platform_integration", 70,
                    "openclaw|open claw|claw|养鱼|养虾|养鱼养虾|agent 平台|agent平台|智能体平台"),
            new Rule("AUTOMATION", "non_coding_automation", 30, "自动回复客户|营销机器人|直播话术|带货脚本")
    );

    private final ObjectMapper objectMapper;

    public RiskUseDetector(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Detection detect(byte[] requestBody) {
        if (requestBody == null || requestBody.length == 0) return null;
        String text = extractText(requestBody);
        if (text.isBlank()) return null;
        String normalized = text.toLowerCase(Locale.ROOT);
        List<String> signals = new ArrayList<>();
        int score = 0;
        int strongestScore = 0;
        String category = null;
        for (Rule rule : RULES) {
            if (rule.pattern().matcher(normalized).find()) {
                signals.add(rule.signal());
                score += rule.score();
                if (rule.score() > strongestScore) {
                    strongestScore = rule.score();
                    category = rule.category();
                }
            }
        }
        if (signals.isEmpty()) return null;
        return new Detection(category, Math.min(score, 100), String.join(",", signals), fingerprint(text), reviewPreview(text));
    }

    private String extractText(byte[] body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            StringBuilder out = new StringBuilder();
            appendText(root.path("messages"), out);
            appendText(root.path("input"), out);
            return out.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private void appendText(JsonNode node, StringBuilder out) {
        if (node.isTextual()) {
            out.append(node.asText()).append('\n');
            return;
        }
        if (node.isArray()) for (JsonNode value : node) appendText(value.path("content"), out);
    }

    private String fingerprint(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(64);
            for (byte value : digest) output.append(String.format("%02x", value));
            return output.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(text.hashCode());
        }
    }

    private String reviewPreview(String text) {
        String compact = text.replaceAll("\\s+", " ").trim();
        compact = compact.replaceAll("(?i)(sk-[a-z0-9_-]{8,}|bearer\\s+[a-z0-9._-]{12,})", "[REDACTED]");
        compact = compact.replaceAll("(?i)(api[_ -]?key|authorization|token|password)\\s*[:=]\\s*[^,;\\s]+", "$1=[REDACTED]");
        return compact.length() <= 280 ? compact : compact.substring(0, 280) + "...";
    }

    private record Rule(String category, String signal, int score, Pattern pattern) {
        private Rule(String category, String signal, int score, String expression) {
            this(category, signal, score, Pattern.compile(expression, Pattern.CASE_INSENSITIVE));
        }
    }

    public record Detection(String category, int score, String signals, String fingerprint, String preview) {
    }
}
