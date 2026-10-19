-- V11: extend request audit facts for administrative usage analytics.
USE ainexus;

ALTER TABLE `request_audit_log`
    ADD COLUMN `account_id` BIGINT NULL COMMENT 'Console account snapshot' AFTER `app_name`,
    ADD COLUMN `user_name` VARCHAR(128) NULL COMMENT 'Console username snapshot' AFTER `account_id`,
    ADD COLUMN `provider` VARCHAR(64) NULL COMMENT 'Upstream provider snapshot' AFTER `target_channel`,
    ADD COLUMN `upstream_account` VARCHAR(128) NULL COMMENT 'Selected upstream channel snapshot' AFTER `provider`,
    ADD COLUMN `api_type` VARCHAR(64) NULL COMMENT 'Gateway API type, e.g. chat_completions' AFTER `upstream_account`,
    ADD COLUMN `model_group_names` VARCHAR(1000) NULL COMMENT 'Authorized model group name snapshot' AFTER `api_type`,
    ADD COLUMN `user_group_names` VARCHAR(1000) NULL COMMENT 'User group name snapshot' AFTER `model_group_names`,
    ADD COLUMN `input_tokens` BIGINT NOT NULL DEFAULT 0 COMMENT 'Input/prompt tokens' AFTER `prompt_tokens`,
    ADD COLUMN `output_tokens` BIGINT NOT NULL DEFAULT 0 COMMENT 'Output/completion tokens' AFTER `input_tokens`,
    ADD COLUMN `total_tokens` BIGINT NOT NULL DEFAULT 0 COMMENT 'Total tokens' AFTER `output_tokens`,
    ADD COLUMN `cache_tokens` BIGINT NOT NULL DEFAULT 0 COMMENT 'Cached input tokens' AFTER `total_tokens`,
    ADD KEY `idx_audit_user_created` (`user_name`, `create_time`),
    ADD KEY `idx_audit_provider_created` (`provider`, `create_time`),
    ADD KEY `idx_audit_api_created` (`api_type`, `create_time`),
    ADD KEY `idx_audit_model_created` (`request_model`, `create_time`);

-- Backfill legacy audit input estimates so totals remain meaningful in historical reports.
UPDATE `request_audit_log`
SET `input_tokens` = COALESCE(`prompt_tokens`, 0),
    `total_tokens` = COALESCE(`prompt_tokens`, 0)
WHERE `input_tokens` = 0 AND `total_tokens` = 0;
