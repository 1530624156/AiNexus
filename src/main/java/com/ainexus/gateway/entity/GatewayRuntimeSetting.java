package com.ainexus.gateway.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("gateway_runtime_setting")
public class GatewayRuntimeSetting {
    @TableId
    private Long id;
    private String accessUrl;
    private Integer protocolConversionEnabled;
    private Integer maxConcurrent;
    private Integer queueCapacity;
    private Integer nonStreamTimeoutSeconds;
    private Integer streamIdleTimeoutSeconds;
    private Integer maxPayloadMb;
    private LocalDateTime updateTime;
}
