package com.ainexus.gateway.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("mail_setting")
public class MailSetting {
    @TableId
    private Long id;
    private Integer enabled;
    private String host;
    private Integer port;
    private String username;
    private String password;
    private String fromAddress;
    private String fromName;
    private Integer startTlsEnabled;
    private Integer sslEnabled;
    private LocalDateTime updateTime;
}
