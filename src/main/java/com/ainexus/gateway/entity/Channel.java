package com.ainexus.gateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 上游渠道主配置。
 *
 * <p>渠道与模型是多对多关系，模型绑定见 {@link ChannelModel}。例如一个 BigModel 渠道可同时绑定
 * GLM-5.3、GLM-5.3-FLASH、GLM-5.2，避免为同一 API Key 和 Base URL 建立重复渠道行。
 */
@Data
@TableName("channel")
public class Channel {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 人可读渠道名称，例如 BigModel-ZhiPu-Primary */
    private String name;

    /** 供应商标识，例如 zhipu/openai/deepseek/qwen/custom */
    private String provider;

    /** 生产请求实际使用的主协议。 */
    private String protocol;

    /** 该渠道支持的协议 JSON 数组；必须包含 protocol。 */
    private String supportedProtocols;

    /** 协议配置 JSON：requestPath/authHeader/authPrefix/headers 等 */
    private String protocolConfig;

    /** 厂商 Base URL */
    private String baseUrl;

    /** 厂商真实 API Key，日志中严禁输出 */
    private String apiKey;

    /** 优先级，数值越大越优先；仅在同优先级内按 weight 分配 */
    private Integer priority;

    /** 同一优先级下的负载权重，至少为 1 */
    private Integer weight;

    /** 单渠道最大并发，0 表示不限制 */
    private Integer maxConcurrent;

    /** 累计失败次数；渠道健康探测/熔断后续可据此进行摘除与告警 */
    private Integer failureCount;

    /** 管理端“测试渠道”默认选中的对外模型名 */
    private String defaultTestModel;

    /** 1=启用，0=停用 */
    private Integer status;

    @TableLogic
    private Integer deleted;

    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
