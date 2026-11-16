package com.ainexus.gateway.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("epay_setting")
public class EpaySetting {
    @TableId private Long id;
    private Integer enabled;
    private String gatewayUrl;
    private String merchantId;
    private String merchantKey;
    private String notifyUrl;
    private String returnUrl;
    private String defaultPaymentType;
    private String signType;
    private LocalDateTime updateTime;
}
