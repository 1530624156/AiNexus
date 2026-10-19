-- V2: migrate the first-version one-model-per-channel schema to channel + channel_model.
-- Target: MySQL 5.7. Run ONCE against an existing ainexus database created by the old schema.sql.
--
-- Safety notes:
-- 1) Backup before execution.
-- 2) DDL auto-commits in MySQL 5.7, so do not interrupt this script.
-- 3) Existing channel IDs, URL, API key, protocol, priority, weight and enabled state are preserved.
USE ainexus;

CREATE TABLE `channel_model` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `channel_id`          BIGINT       NOT NULL COMMENT 'channel.id',
    `model_name`          VARCHAR(128) NOT NULL COMMENT 'Public model name accepted by gateway',
    `upstream_model_name` VARCHAR(128) NULL COMMENT 'Actual provider model name; null means same as model_name',
    `status`              TINYINT      NOT NULL DEFAULT 1 COMMENT '1=enabled, 0=disabled',
    `deleted`             TINYINT      NOT NULL DEFAULT 0 COMMENT 'Logical delete',
    `remark`              VARCHAR(512) NULL COMMENT 'Operator note',
    `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_channel_model` (`channel_id`, `model_name`),
    KEY `idx_model_enabled` (`model_name`, `status`, `deleted`),
    KEY `idx_channel_enabled` (`channel_id`, `status`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Models bound to upstream channels';

-- Copy every old one-model channel row into an explicit model binding before model_name is dropped.
INSERT INTO `channel_model` (`channel_id`, `model_name`, `upstream_model_name`, `status`, `deleted`, `create_time`, `update_time`, `remark`)
SELECT `id`, `model_name`, NULL, `status`, `deleted`, `create_time`, `update_time`, 'Migrated from channel.model_name'
FROM `channel`;

ALTER TABLE `channel`
    DROP INDEX `idx_model_status`,
    CHANGE COLUMN `channel_name` `name` VARCHAR(128) NOT NULL COMMENT 'Human-readable channel name',
    ADD COLUMN `provider` VARCHAR(64) NOT NULL DEFAULT 'custom' AFTER `name`,
    ADD COLUMN `protocol_config` TEXT NULL COMMENT 'JSON protocol override: requestPath/authHeader/authPrefix/headers' AFTER `protocol`,
    ADD COLUMN `max_concurrent` INT NOT NULL DEFAULT 0 COMMENT 'Maximum in-flight requests; 0 means unlimited' AFTER `weight`,
    ADD COLUMN `default_test_model` VARCHAR(128) NULL COMMENT 'Default public model used by management/test UI' AFTER `max_concurrent`,
    ADD COLUMN `remark` VARCHAR(512) NULL COMMENT 'Operator note' AFTER `deleted`;

-- Preserve an operator-friendly test model based on the old binding.
UPDATE `channel` c
JOIN `channel_model` cm ON cm.channel_id = c.id AND cm.deleted = 0
SET c.default_test_model = cm.model_name
WHERE c.default_test_model IS NULL;

ALTER TABLE `channel`
    DROP COLUMN `model_name`,
    MODIFY COLUMN `api_key` VARCHAR(512) NOT NULL COMMENT 'Provider API key, must be encrypted in production',
    ADD UNIQUE KEY `uk_channel_name` (`name`),
    ADD KEY `idx_channel_enabled_priority` (`status`, `deleted`, `priority`);

-- Optional provider classification for the first-version sample data.
UPDATE `channel` SET `provider` = 'openai' WHERE `name` = 'openai-official';
UPDATE `channel` SET `provider` = 'deepseek' WHERE `name` = 'deepseek-official';
UPDATE `channel` SET `provider` = 'qwen' WHERE `name` = 'dashscope-openai-compat';

-- Verification queries:
-- SELECT c.id, c.name, c.provider, c.priority, c.weight, c.max_concurrent,
--        GROUP_CONCAT(cm.model_name ORDER BY cm.model_name SEPARATOR ', ') AS bound_models
-- FROM channel c LEFT JOIN channel_model cm ON cm.channel_id = c.id AND cm.deleted = 0
-- GROUP BY c.id, c.name, c.provider, c.priority, c.weight, c.max_concurrent;
