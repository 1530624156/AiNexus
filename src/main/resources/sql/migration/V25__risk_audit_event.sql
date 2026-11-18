-- V25: privacy-preserving manual review queue for potentially off-scope usage.
USE ainexus;

CREATE TABLE IF NOT EXISTS `risk_audit_event` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `request_id` VARCHAR(64) NULL COMMENT 'Gateway request correlation id',
    `account_id` BIGINT NULL,
    `user_name` VARCHAR(128) NULL,
    `app_name` VARCHAR(128) NULL,
    `risk_category` VARCHAR(64) NOT NULL COMMENT 'Candidate category only',
    `risk_score` INT NOT NULL,
    `matched_signals` VARCHAR(512) NOT NULL COMMENT 'Rule identifiers, no raw prompt',
    `content_fingerprint` VARCHAR(64) NOT NULL COMMENT 'SHA-256 of extracted text',
    `content_preview` VARCHAR(280) NULL COMMENT 'Credential-redacted review excerpt; never full request body',
    `review_status` VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_risk_created` (`create_time`),
    KEY `idx_risk_category_created` (`risk_category`, `create_time`),
    KEY `idx_risk_user_created` (`user_name`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Manual acceptable-use review candidates; never enforcement decisions';
