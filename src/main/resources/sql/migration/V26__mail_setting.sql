-- V26: singleton SMTP settings for administrator-configured outbound email.
USE ainexus;

CREATE TABLE IF NOT EXISTS `mail_setting` (
    `id` BIGINT NOT NULL COMMENT 'Singleton row, always 1',
    `enabled` TINYINT NOT NULL DEFAULT 0,
    `host` VARCHAR(255) NULL,
    `port` INT NOT NULL DEFAULT 587,
    `username` VARCHAR(255) NULL,
    `password` VARCHAR(512) NULL COMMENT 'SMTP password or provider app password; server-side only',
    `from_address` VARCHAR(255) NULL,
    `from_name` VARCHAR(128) NULL,
    `start_tls_enabled` TINYINT NOT NULL DEFAULT 1,
    `ssl_enabled` TINYINT NOT NULL DEFAULT 0,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SMTP mail delivery configuration';

INSERT INTO `mail_setting` (`id`) VALUES (1)
ON DUPLICATE KEY UPDATE `id` = `id`;
