package com.ainexus.gateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Administrator-managed release note shown in the console changelog. */
@Data
@TableName("release_note")
public class ReleaseNote {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String version;
    private String title;
    private String content;
    private String status;
    private LocalDateTime publishedAt;
    private String createdBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
