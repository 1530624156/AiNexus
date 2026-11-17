package com.ainexus.gateway.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Singleton row holding the global advertisement leasing contact. */
@Data
@TableName("ad_setting")
public class AdSetting {
    @TableId
    private Long id;
    private String contactType;
    private String contactValue;
    private LocalDateTime updateTime;
}