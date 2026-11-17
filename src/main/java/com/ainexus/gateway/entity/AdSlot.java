package com.ainexus.gateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** One commercial advertisement slot: leasing, live or closed. */
@Data
@TableName("ad_slot")
public class AdSlot {
    @TableId(type = IdType.INPUT)
    private String slotKey;
    private String status;
    private String logoUrl;
    private String title;
    private String subtitle;
    private String buttonText;
    private String linkUrl;
    private LocalDateTime updateTime;
}