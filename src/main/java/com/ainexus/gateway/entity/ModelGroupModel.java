package com.ainexus.gateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("model_group_model")
public class ModelGroupModel {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long modelGroupId;
    private String modelName;
    private LocalDateTime createTime;
}
