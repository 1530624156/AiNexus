package com.ainexus.gateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 内部应用令牌。
 * Token 不承载计费或终端用户信息，只定义网关访问权限和可选限流配置。
 */
@Data
@TableName("gateway_token")
public class Token {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 内部 Bearer Token；数据库应采用加密列或外部密钥管理服务保护 */
    private String token;

    /** 所属内部应用，例如 ai-platform / crm / data-pipeline */
    private String appName;

    /** 用户端 API Key 所属控制台账号；管理员创建的内部 Token 可为空。 */
    private Long accountId;

    /** JSON 限流配置，例如 {"permitsPerSecond":20,"burst":40}；为空使用全局默认 */
    private String rateLimitConfig;

    /** 1=启用，0=禁用 */
    private Integer status;

    @TableLogic
    private Integer deleted;

    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
