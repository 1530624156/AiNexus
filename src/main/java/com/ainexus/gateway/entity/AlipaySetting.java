package com.ainexus.gateway.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("alipay_setting")
public class AlipaySetting {
    @TableId private Long id;
    private Integer enabled;
    private String appId;
    private String sellerId;
    private String privateKey;
    private String alipayPublicKey;
    private String gatewayUrl;
    private String notifyUrl;
    private String returnUrl;
    private String signType;
    private String charset;
    private String format;
    private LocalDateTime updateTime;
}
