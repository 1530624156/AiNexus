-- V14: configurable GitHub Star access gate for USER portal API keys.
USE ainexus;

CREATE TABLE IF NOT EXISTS `github_star_setting` (
    `id` BIGINT NOT NULL COMMENT 'Singleton row, always 1',
    `enabled` TINYINT NOT NULL DEFAULT 0 COMMENT '1=require GitHub Star for USER API keys',
    `repository` VARCHAR(255) NULL COMMENT 'owner/repository',
    `public_base_url` VARCHAR(512) NULL COMMENT 'Public web origin for OAuth callback',
    `oauth_client_id` VARCHAR(255) NULL,
    `oauth_client_secret` VARCHAR(512) NULL COMMENT 'Sensitive; never returned to browser',
    `daily_check_time` VARCHAR(5) NOT NULL DEFAULT '06:00' COMMENT 'Daily GitHub Star verification time, HH:mm',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='GitHub Star gate configuration';

INSERT INTO `github_star_setting` (`id`, `enabled`) VALUES (1, 0)
ON DUPLICATE KEY UPDATE `id` = `id`;

CREATE TABLE IF NOT EXISTS `account_github_binding` (
    `account_id` BIGINT NOT NULL,
    `github_login` VARCHAR(128) NOT NULL,
    `access_token` VARCHAR(512) NOT NULL COMMENT 'OAuth access token, server-side only',
    `star_verified_at` DATETIME NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`account_id`),
    KEY `idx_github_login` (`github_login`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='USER GitHub OAuth bindings';
