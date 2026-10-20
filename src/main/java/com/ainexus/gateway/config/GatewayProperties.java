package com.ainexus.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关自身配置(prefix = gateway)。
 */
@Data
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    /** 内部应用鉴权配置 */
    private Auth auth = new Auth();

    /** 极简 Admin API 静态口令配置 */
    private Admin admin = new Admin();

    /** Console account/session settings. */
    private Console console = new Console();

    /** OkHttp 转发客户端配置 */
    private HttpClient httpClient = new HttpClient();

    /** CORS 配置 */
    private Cors cors = new Cors();

    /** Prompt cache, usage reporting, and cache-affinity routing settings. */
    private PromptCache promptCache = new PromptCache();

    /** 静态降级渠道: MySQL 不可用时的兜底路由, 亦可用于本地联调 Mock 上游 */
    private List<StaticChannel> degradedChannels = new ArrayList<>();

    @Data
    public static class Auth {
        /** 是否开启内部鉴权(仅建议本机调试时关闭) */
        private boolean enabled = true;
        /** Mock 首版: 内部 Key 白名单; Redis 不可用时使用 */
        private List<String> internalKeys = new ArrayList<>();
    }

    @Data
    public static class Admin {
        /** 管理 API Header: Admin-Authorization。生产环境必须通过环境变量覆盖。 */
        private String secret = "change_me";
    }

    @Data
    public static class Console {
        /** HMAC key for signed browser console sessions. Override in production. */
        private String sessionSecret = "change_me_console_session_secret";
        /** Bootstrap administrator password; account is created only if no ADMIN account exists. */
        private String bootstrapAdminPassword = "admin";
    }

    @Data
    public static class HttpClient {
        private int poolMaxIdle = 64;
        private long poolKeepAliveSeconds = 300;
        private long connectTimeoutMs = 10000;
        private long readTimeoutMs = 180000;
        /** SSE 流式调用总时长上限, 0 = 不设上限(由上游/客户端断开自然结束) */
        private long streamCallTimeoutMs = 0;
        /** SSE 流式 read 超时(两次 chunk 之间的最大间隔), 0 = 不设上限 */
        private long streamReadTimeoutMs = 0;
        /** 是否打印上游转发日志(含请求头, 生产慎开) */
        private boolean logRequest = false;
    }

    @Data
    public static class Cors {
        private String allowedOrigins = "*";
    }

    @Data
    public static class PromptCache {
        /** Request OpenAI-compatible SSE usage frames when the client did not opt out. */
        private boolean streamUsage = true;
        /** Emit Anthropic cache_control checkpoints for stable prompt prefixes. */
        private boolean anthropicCacheControl = true;
        /** Keep related conversations on one upstream account to preserve provider-side cache affinity. */
        private boolean stickyRouting = true;
        /** Avoid cache-write overhead for short prompts that are unlikely to benefit. */
        private int minCacheableChars = 1500;
    }

    @Data
    public static class StaticChannel {
        private String modelName;
        private String channelName;
        /** 上游协议: openai(直接透传) / native(预留协议转换) */
        private String protocol = "openai";
        private String baseUrl;
        private String apiKey;
        private int weight = 1;
        private int priority = 0;
    }
}