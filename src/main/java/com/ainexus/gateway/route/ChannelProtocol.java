package com.ainexus.gateway.route;

import java.util.Locale;

/** 网关支持的上游对话协议。 */
public enum ChannelProtocol {
    OPENAI_COMPLETIONS("openai-completions"),
    OPENAI_RESPONSES("openai-responses"),
    ANTHROPIC_MESSAGES("anthropic-messages");

    private final String value;

    ChannelProtocol(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /** 兼容第一版数据库中的 openai 值。 */
    public static ChannelProtocol parse(String raw) {
        if (raw == null || raw.isBlank() || "openai".equalsIgnoreCase(raw)) {
            return OPENAI_COMPLETIONS;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (ChannelProtocol protocol : values()) {
            if (protocol.value.equals(normalized)) {
                return protocol;
            }
        }
        throw new IllegalArgumentException("Unsupported channel protocol: " + raw);
    }
}
