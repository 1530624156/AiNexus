package com.ainexus.gateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 渠道与对外模型的绑定关系。
 *
 * <p>一个 channel_id 可有多条绑定，例如 BigModel -> GLM-5.3 / GLM-5.3-FLASH / GLM-5.2。
 * upstreamModelName 为空时将客户端 model 原样发送给厂商；不为空时仅改写上游请求中的 model 字段。
 */
@Data
@TableName("channel_model")
public class ChannelModel {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long channelId;

    /** 网关对内统一暴露的模型名 */
    private String modelName;

    /** 厂商实际模型名，空值表示同 modelName */
    private String upstreamModelName;

    /** 1=启用，0=停用 */
    private Integer status;

    @TableLogic
    private Integer deleted;

    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
