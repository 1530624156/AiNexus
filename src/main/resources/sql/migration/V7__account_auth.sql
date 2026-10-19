-- V7: console accounts for administrator and user portals.
USE ainexus;

CREATE TABLE IF NOT EXISTS `account` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `username`      VARCHAR(64)  NOT NULL COMMENT 'Unique login name',
    `password_hash` VARCHAR(512) NOT NULL COMMENT 'PBKDF2 password hash',
    `role`          VARCHAR(16)  NOT NULL DEFAULT 'USER' COMMENT 'ADMIN or USER',
    `status`        TINYINT      NOT NULL DEFAULT 1 COMMENT '1=enabled, 0=disabled',
    `remark`        VARCHAR(512) NULL COMMENT 'Operator note',
    `deleted`       TINYINT      NOT NULL DEFAULT 0 COMMENT 'Logical delete',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_account_username` (`username`),
    KEY `idx_account_role_status` (`role`, `status`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Console user accounts';
