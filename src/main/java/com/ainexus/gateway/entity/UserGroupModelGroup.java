package com.ainexus.gateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_group_model_group")
public class UserGroupModelGroup {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userGroupId;
    private Long modelGroupId;
    private LocalDateTime createTime;
}
