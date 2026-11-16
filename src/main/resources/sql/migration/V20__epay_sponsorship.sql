-- V20: Epay configuration and voluntary sponsorship orders.
USE ainexus;

CREATE TABLE IF NOT EXISTS `epay_setting` (
    `id` BIGINT NOT NULL COMMENT 'Singleton row, always 1',
    `enabled` TINYINT NOT NULL DEFAULT 0,
    `gateway_url` VARCHAR(512) NULL COMMENT 'Epay submit endpoint, for example https://pay.example.com/submit.php',
    `merchant_id` VARCHAR(64) NULL COMMENT 'Epay pid',
    `merchant_key` VARCHAR(512) NULL COMMENT 'Epay key; server-side only',
    `notify_url` VARCHAR(512) NULL,
    `return_url` VARCHAR(512) NULL,
    `default_payment_type` VARCHAR(32) NOT NULL DEFAULT 'alipay' COMMENT 'alipay, wxpay, qqpay or provider-defined type',
    `sign_type` VARCHAR(16) NOT NULL DEFAULT 'MD5',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Epay sponsorship configuration';

INSERT INTO `epay_setting` (`id`) VALUES (1)
ON DUPLICATE KEY UPDATE `id` = `id`;

CREATE TABLE IF NOT EXISTS `sponsorship_order` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `out_trade_no` VARCHAR(64) NOT NULL COMMENT 'Merchant order number',
    `account_id` BIGINT NOT NULL COMMENT 'Sponsor account id',
    `sponsor_name` VARCHAR(128) NOT NULL COMMENT 'Sponsor username snapshot',
    `amount` DECIMAL(12,2) NOT NULL,
    `currency` VARCHAR(8) NOT NULL DEFAULT 'CNY',
    `subject` VARCHAR(128) NOT NULL,
    `payment_type` VARCHAR(32) NULL COMMENT 'Epay payment type',
    `status` VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, PAID, CLOSED',
    `trade_no` VARCHAR(64) NULL COMMENT 'Epay platform trade number',
    `paid_at` DATETIME NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sponsorship_out_trade_no` (`out_trade_no`),
    KEY `idx_sponsorship_account_created` (`account_id`, `create_time`),
    KEY `idx_sponsorship_status_created` (`status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Voluntary sponsorship payment orders';
