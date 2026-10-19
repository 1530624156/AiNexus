package com.ainexus.gateway.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("github_star_setting")
public class GithubStarSetting {
    @TableId private Long id;
    private Integer enabled;
    private String repository;
    private String publicBaseUrl;
    private String oauthClientId;
    private String oauthClientSecret;
    private String dailyCheckTime;
    private LocalDateTime updateTime;
}
