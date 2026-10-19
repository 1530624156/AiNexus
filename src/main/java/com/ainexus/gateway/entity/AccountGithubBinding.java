package com.ainexus.gateway.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("account_github_binding")
public class AccountGithubBinding {
    @TableId private Long accountId;
    private String githubLogin;
    private String accessToken;
    private LocalDateTime starVerifiedAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
