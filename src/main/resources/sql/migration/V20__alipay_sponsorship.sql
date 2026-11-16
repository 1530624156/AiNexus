-- V20: Alipay configuration and voluntary sponsorship orders.
USE ainexus;

CREATE TABLE IF NOT EXISTS `alipay_setting` (
    `id` BIGINT NOT NULL COMMENT 'Singleton row, always 1',
    `enabled` TINYINT NOT NULL DEFAULT 0,
    `app_id` VARCHAR(64) NULL,
    `seller_id` VARCHAR(64) NULL COMMENT 'Merchant Alipay user ID/PID',
    `private_key` TEXT NULL COMMENT 'Merchant RSA private key; server-side only',
    `alipay_public_key` TEXT NULL COMMENT 'Alipay RSA public key',
    `gateway_url` VARCHAR(512) NOT NULL DEFAULT 'https://openapi.alipay.com/gateway.do',
    `notify_url` VARCHAR(512) NULL,
    `return_url` VARCHAR(512) NULL,
    `sign_type` VARCHAR(16) NOT NULL DEFAULT 'RSA2',
    `charset` VARCHAR(32) NOT NULL DEFAULT 'UTF-8',
    `format` VARCHAR(16) NOT NULL DEFAULT 'json',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Alipay sponsorship configuration';

INSERT INTO `alipay_setting` (`id`) VALUES (1)
ON DUPLICATE KEY UPDATE `id` = `id`;

CREATE TABLE IF NOT EXISTS `sponsorship_order` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `out_trade_no` VARCHAR(64) NOT NULL COMMENT 'Merchant order number',
    `account_id` BIGINT NOT NULL COMMENT 'Sponsor account id',
    `sponsor_name` VARCHAR(128) NOT NULL COMMENT 'Sponsor username snapshot',
    `amount` DECIMAL(12,2) NOT NULL,
    `currency` VARCHAR(8) NOT NULL DEFAULT 'CNY',
    `subject` VARCHAR(128) NOT NULL,
    `status` VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, PAID, CLOSED',
    `trade_no` VARCHAR(64) NULL COMMENT 'Alipay trade number',
    `paid_at` DATETIME NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sponsorship_out_trade_no` (`out_trade_no`),
    KEY `idx_sponsorship_account_created` (`account_id`, `create_time`),
    KEY `idx_sponsorship_status_created` (`status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Voluntary sponsorship payment orders';
