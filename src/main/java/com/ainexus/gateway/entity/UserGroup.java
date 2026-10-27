package com.ainexus.gateway.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_group")
public class UserGroup {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private Integer maxConcurrent;
    private Integer apiKeyMaxConcurrent;
    private Long tokenQuota;
    private String quotaPeriod;
    private Long memberTokenQuota;
    private Integer memberMaxConcurrent;
    /** Allowed call window start, HH:mm; both null/blank means the group is callable all day. */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String allowTimeStart;
    /** Allowed call window end, HH:mm; an end not later than the start crosses midnight. */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String allowTimeEnd;
    private String remark;
    @TableLogic
    private Integer deleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
