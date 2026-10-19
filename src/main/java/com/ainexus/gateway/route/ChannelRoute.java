package com.ainexus.gateway.route;

import lombok.Data;
import lombok.ToString;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 已展开为“渠道 + 模型绑定”的运行时路由对象。
 * 经 {@code @ToString(exclude = {"apiKey", "extraHeaders"})} 防止敏感配置进入日志。
 */
@Data
@ToString(exclude = {"apiKey", "extraHeaders"})
public class ChannelRoute {

    /** 渠道主表 ID；同一渠道的多个模型绑定共享该值 */
    private Long id;
    /** 绑定表 ID，用于审计与后续模型级开关 */
    private Long channelModelId;
    /** 网关对外模型名 */
    private String modelName;
    /** 上游真实模型名，为空时原样传递 modelName */
    private String upstreamModelName;
    private String channelName;
    private String provider;
    /** 上游协议：当前 openai 兼容协议透传；native 为未来适配预留 */
    private String protocol = "openai";
    private String baseUrl;
    private String apiKey;
    /** 协议配置中的覆盖路径；为空则保留客户端请求路径 */
    private String requestPath;
    /** 协议配置中的鉴权头，默认 Authorization */
    private String authHeader = "Authorization";
    /** 协议配置中的 Key 前缀，默认 Bearer + 空格 */
    private String authPrefix = "Bearer ";
    /** 协议配置中的固定附加请求头 */
    private Map<String, String> extraHeaders = new LinkedHashMap<>();
    private int weight = 1;
    private int priority = 0;
    /** 单渠道最大并发，0 表示无限制 */
    private int maxConcurrent = 0;
    private String defaultTestModel;
}
