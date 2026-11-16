package com.ainexus.gateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("sponsorship_order")
public class SponsorshipOrder {
    @TableId(type = IdType.AUTO) private Long id;
    private String outTradeNo;
    private Long accountId;
    private String sponsorName;
    private BigDecimal amount;
    private String currency;
    private String subject;
    private String paymentType;
    private String status;
    private String tradeNo;
    private LocalDateTime paidAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
