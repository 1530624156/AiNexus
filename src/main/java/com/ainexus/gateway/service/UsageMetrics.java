package com.ainexus.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;

/** Normalized token usage across OpenAI-compatible, Responses, and Anthropic APIs. */
public record UsageMetrics(Long inputTokens, Long outputTokens, Long totalTokens,
                           Long cacheReadTokens, Long cacheWriteTokens) {

    public static UsageMetrics fromUsage(JsonNode usage) {
        if (usage == null || !usage.isObject()) {
            return null;
        }
        Long cacheRead = firstNumber(usage,
                "prompt_tokens_details.cached_tokens",
                "input_tokens_details.cached_tokens",
                "cache_read_input_tokens",
                "prompt_cache_hit_tokens",
                "cached_tokens");
        Long cacheWrite = firstNumber(usage,
                "prompt_tokens_details.cache_creation_tokens",
                "input_tokens_details.cache_creation_tokens",
                "cache_creation_input_tokens",
                "cache_creation_tokens");
        Long input = firstNumber(usage, "prompt_tokens", "input_tokens");
        Long output = firstNumber(usage, "completion_tokens", "output_tokens");

        // The audit contract is non-cached input + output + cache read = total.
        // OpenAI-compatible prompt_tokens already includes cache reads, while Anthropic
        // reports normal input separately and reports cache creation as additional input.
        if (usage.has("cache_read_input_tokens") || usage.has("cache_creation_input_tokens")) {
            input = sum(input, cacheWrite);
        } else if (input != null && cacheRead != null) {
            input = Math.max(0L, input - cacheRead);
        }
        Long reportedTotal = number(usage, "total_tokens");
        if (input == null && output == null && cacheRead == null && reportedTotal != null) {
            input = reportedTotal;
        }
        Long total = input == null && output == null && cacheRead == null ? reportedTotal : sum(input, output, cacheRead);
        if (input == null && output == null && total == null && cacheRead == null && cacheWrite == null) {
            return null;
        }
        return new UsageMetrics(input, output, total, cacheRead, cacheWrite);
    }

    /** Combines cumulative SSE usage frames, retaining the newest/largest known value. */
    public UsageMetrics merge(UsageMetrics other) {
        if (other == null) {
            return this;
        }
        Long input = max(inputTokens, other.inputTokens);
        Long output = max(outputTokens, other.outputTokens);
        Long cacheRead = max(cacheReadTokens, other.cacheReadTokens);
        Long cacheWrite = max(cacheWriteTokens, other.cacheWriteTokens);
        Long total = input == null && output == null && cacheRead == null
                ? max(totalTokens, other.totalTokens) : sum(input, output, cacheRead);
        return new UsageMetrics(input, output, total, cacheRead, cacheWrite);
    }

    private static Long firstNumber(JsonNode node, String... paths) {
        for (String path : paths) {
            JsonNode candidate = node;
            for (String segment : path.split("\\.")) {
                candidate = candidate.path(segment);
            }
            if (candidate.isNumber()) {
                return candidate.longValue();
            }
        }
        return null;
    }

    private static Long number(JsonNode node, String field) {
        JsonNode candidate = node.path(field);
        return candidate.isNumber() ? candidate.longValue() : null;
    }

    private static Long sum(Long... values) {
        long result = 0;
        boolean present = false;
        for (Long value : values) {
            if (value != null) {
                result += value;
                present = true;
            }
        }
        return present ? result : null;
    }

    private static Long max(Long first, Long second) {
        if (first == null) return second;
        if (second == null) return first;
        return Math.max(first, second);
    }
}
