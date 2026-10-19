-- V4: internal application Token table and channel failure counter.
-- MySQL 5.7 compatible. Run once against an upgraded ainexus database.
USE ainexus;

CREATE TABLE IF NOT EXISTS `gateway_token` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `token`             VARCHAR(512) NOT NULL COMMENT 'Internal Bearer Token; encrypt at rest in production',
    `app_name`          VARCHAR(128) NOT NULL COMMENT 'Owning internal application',
    `rate_limit_config` TEXT         NULL COMMENT 'Optional JSON, e.g. {"permitsPerSecond":20,"burst":40}',
    `status`            TINYINT      NOT NULL DEFAULT 1 COMMENT '1=enabled, 0=disabled',
    `deleted`           TINYINT      NOT NULL DEFAULT 0 COMMENT 'Logical delete',
    `remark`            VARCHAR(512) NULL COMMENT 'Operator note',
    `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_gateway_token` (`token`),
    KEY `idx_token_enabled` (`status`, `deleted`),
    KEY `idx_token_app` (`app_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Internal application tokens';

ALTER TABLE `channel`
    ADD COLUMN `failure_count` INT NOT NULL DEFAULT 0 COMMENT 'Cumulative upstream failure count' AFTER `max_concurrent`;

-- Bootstrap development Token. Replace it in real environments; this statement is idempotent.
INSERT INTO `gateway_token` (`token`, `app_name`, `rate_limit_config`, `status`, `remark`)
SELECT 'sk-ainexus-dev-team-a', 'dev-team-a', NULL, 1, 'Bootstrap development token from application.yml'
WHERE NOT EXISTS (SELECT 1 FROM `gateway_token` WHERE `token` = 'sk-ainexus-dev-team-a');

INSERT INTO `gateway_token` (`token`, `app_name`, `rate_limit_config`, `status`, `remark`)
SELECT 'sk-ainexus-dev-team-b', 'dev-team-b', NULL, 1, 'Bootstrap development token from application.yml'
WHERE NOT EXISTS (SELECT 1 FROM `gateway_token` WHERE `token` = 'sk-ainexus-dev-team-b');

-- Example for a rate-limited application:
-- INSERT INTO gateway_token (token, app_name, rate_limit_config, status)
-- VALUES ('sk-internal-real-token', 'crm-service', '{"permitsPerSecond":20,"burst":40}', 1);
