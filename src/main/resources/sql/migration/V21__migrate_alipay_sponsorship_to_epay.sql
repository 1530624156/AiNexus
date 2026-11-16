-- V21: upgrade installations that already executed the former V20 Alipay migration.
-- Retains historical orders and adds the Epay configuration required by the replacement flow.
USE ainexus;

CREATE TABLE IF NOT EXISTS `epay_setting` (
    `id` BIGINT NOT NULL COMMENT 'Singleton row, always 1',
    `enabled` TINYINT NOT NULL DEFAULT 0,
    `gateway_url` VARCHAR(512) NULL COMMENT 'Epay submit endpoint, for example https://pay.example.com/submit.php',
    `merchant_id` VARCHAR(64) NULL COMMENT 'Epay pid',
    `merchant_key` VARCHAR(512) NULL COMMENT 'Epay key; server-side only',
    `notify_url` VARCHAR(512) NULL,
    `return_url` VARCHAR(512) NULL,
    `default_payment_type` VARCHAR(32) NOT NULL DEFAULT 'alipay',
    `sign_type` VARCHAR(16) NOT NULL DEFAULT 'MD5',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Epay sponsorship configuration';

INSERT INTO `epay_setting` (`id`) VALUES (1)
ON DUPLICATE KEY UPDATE `id` = `id`;

-- Add the payment type only when upgrading a database created by the former Alipay V20 script.
SET @payment_type_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'sponsorship_order' AND column_name = 'payment_type'
);
SET @payment_type_sql = IF(@payment_type_exists = 0,
    'ALTER TABLE `sponsorship_order` ADD COLUMN `payment_type` VARCHAR(32) NULL COMMENT ''Epay payment type'' AFTER `subject`',
    'SELECT 1');
PREPARE payment_type_statement FROM @payment_type_sql;
EXECUTE payment_type_statement;
DEALLOCATE PREPARE payment_type_statement;

-- The former Alipay callback route is retired with this release. Existing unpaid
-- Alipay orders cannot be verified by Epay and must not later be credited.
UPDATE `sponsorship_order`
SET `status` = 'CLOSED'
WHERE `status` = 'PENDING' AND (`payment_type` IS NULL OR `payment_type` = 'alipay');
