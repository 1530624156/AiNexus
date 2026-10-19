-- V8: associate user portal API keys with console accounts.
USE ainexus;

ALTER TABLE `gateway_token`
    ADD COLUMN `account_id` BIGINT NULL COMMENT 'Owning console account for USER portal API keys' AFTER `app_name`,
    ADD KEY `idx_token_account_enabled` (`account_id`, `status`, `deleted`);
