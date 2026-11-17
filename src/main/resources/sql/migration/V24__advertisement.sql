-- V24: Commercial advertisement slots plus the global leasing contact.
USE ainexus;

CREATE TABLE IF NOT EXISTS `ad_setting` (
    `id`            BIGINT       NOT NULL COMMENT 'Singleton row, always 1',
    `contact_type`  VARCHAR(16)  NOT NULL DEFAULT 'WECHAT' COMMENT 'WECHAT, QQ, EMAIL, QR or LINK',
    `contact_value` VARCHAR(512) NULL COMMENT 'WeChat/QQ id, e-mail address, QR image URL or contact link',
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Global advertisement leasing contact';

INSERT INTO `ad_setting` (`id`) VALUES (1)
ON DUPLICATE KEY UPDATE `id` = `id`;

CREATE TABLE IF NOT EXISTS `ad_slot` (
    `slot_key`    VARCHAR(32)  NOT NULL COMMENT 'LOGIN_ASIDE, USER_HERO or USER_SIDEBAR',
    `status`      VARCHAR(16)  NOT NULL DEFAULT 'LEASING' COMMENT 'LEASING, ACTIVE or CLOSED',
    `logo_url`    VARCHAR(512) NULL COMMENT 'Advertiser logo URL; http(s) or site-relative',
    `title`       VARCHAR(128) NULL COMMENT 'Primary headline; also the leasing copy when LEASING',
    `subtitle`    VARCHAR(255) NULL COMMENT 'Secondary copy or leasing pitch',
    `button_text` VARCHAR(32)  NULL COMMENT 'Call-to-action label',
    `link_url`    VARCHAR(512) NULL COMMENT 'Click-through URL; http(s) or site-relative',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`slot_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Commercial advertisement slot configuration';

INSERT INTO `ad_slot` (`slot_key`, `status`) VALUES
    ('LOGIN_ASIDE', 'LEASING'),
    ('USER_HERO', 'LEASING'),
    ('USER_SIDEBAR', 'LEASING')
ON DUPLICATE KEY UPDATE `slot_key` = `slot_key`;