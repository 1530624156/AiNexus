package com.ainexus.gateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * A privacy-preserving candidate event for manual acceptable-use review.
 * Request bodies and prompt excerpts are intentionally never stored here.
 */
@Data
@TableName("risk_audit_event")
public class RiskAuditEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String requestId;
    private Long accountId;
    private String userName;
    private String appName;
    private String riskCategory;
    private Integer riskScore;
    private String matchedSignals;
    private String contentFingerprint;
    private String contentPreview;
    private String reviewStatus;
    private LocalDateTime createTime;
}
