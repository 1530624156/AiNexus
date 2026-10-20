-- V15: persist GitHub OAuth refresh-token metadata for expiring user authorization tokens.
USE ainexus;

ALTER TABLE `account_github_binding`
    ADD COLUMN `refresh_token` VARCHAR(512) NULL COMMENT 'GitHub OAuth refresh token, server-side only' AFTER `access_token`,
    ADD COLUMN `access_token_expires_at` DATETIME NULL COMMENT 'Access token expiration time' AFTER `refresh_token`,
    ADD COLUMN `refresh_token_expires_at` DATETIME NULL COMMENT 'Refresh token expiration time' AFTER `access_token_expires_at`;
