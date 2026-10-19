-- V5: high-throughput asynchronous request audit table.
-- MySQL 5.7 compatible. No foreign keys by design: audit writes must not be blocked by token/channel lifecycle operations.
USE ainexus;

CREATE TABLE IF NOT EXISTS `request_audit_log` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `app_name`       VARCHAR(128) NULL COMMENT 'Authenticated internal application name',
    `request_model`  VARCHAR(128) NULL COMMENT 'Requested public model',
    `target_channel` VARCHAR(128) NULL COMMENT 'Final attempted upstream channel',
    `prompt_tokens`  INT          NULL COMMENT 'Estimated prompt tokens; null for unsupported/multimodal requests',
    `status_code`    INT          NOT NULL COMMENT 'Gateway response HTTP status',
    `latency_ms`     BIGINT       NOT NULL COMMENT 'End-to-end gateway latency in milliseconds',
    `error_msg`      VARCHAR(1000) NULL COMMENT 'Truncated error summary; never persist request body or API keys',
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_audit_created` (`create_time`),
    KEY `idx_audit_app_created` (`app_name`, `create_time`),
    KEY `idx_audit_channel_created` (`target_channel`, `create_time`),
    KEY `idx_audit_status_created` (`status_code`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Asynchronous gateway request audit log';

-- Retention recommendation: run a scheduled maintenance job, for example:
-- DELETE FROM request_audit_log WHERE create_time < NOW() - INTERVAL 90 DAY;
