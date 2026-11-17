package com.ainexus.gateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Console user feedback with a single, editable administrator reply. */
@Data
@TableName("user_feedback")
public class UserFeedback {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long accountId;
    private String username;
    private String category;
    private String content;
    private String contact;
    private String status;
    private String replyContent;
    private String repliedBy;
    private LocalDateTime repliedAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}