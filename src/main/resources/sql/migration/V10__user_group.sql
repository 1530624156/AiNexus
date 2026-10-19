-- V10: user groups, member assignment, model-group authorization and quota policies.
USE ainexus;

CREATE TABLE IF NOT EXISTS `user_group` (
    `id`                       BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `name`                     VARCHAR(128) NOT NULL COMMENT 'Unique group name',
    `max_concurrent`           INT          NOT NULL DEFAULT 0 COMMENT 'Group max concurrent requests; 0=unlimited',
    `api_key_max_concurrent`   INT          NOT NULL DEFAULT 0 COMMENT 'Max concurrent requests per API key; 0=unlimited',
    `token_quota`              BIGINT       NOT NULL DEFAULT 0 COMMENT 'Group max token quota per period; 0=unlimited',
    `quota_period`             VARCHAR(16)  NOT NULL DEFAULT 'MONTH' COMMENT 'DAY/WEEK/MONTH/YEAR',
    `member_token_quota`       BIGINT       NOT NULL DEFAULT 0 COMMENT 'Per-member max token quota per period; 0=unlimited',
    `member_max_concurrent`    INT          NOT NULL DEFAULT 0 COMMENT 'Per-member max concurrent requests; 0=unlimited',
    `remark`                   VARCHAR(512) NULL COMMENT 'Operator note',
    `deleted`                  TINYINT      NOT NULL DEFAULT 0 COMMENT 'Logical delete',
    `create_time`              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_group_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='User access groups and quotas';

CREATE TABLE IF NOT EXISTS `user_group_member` (
    `id`            BIGINT      NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `user_group_id` BIGINT      NOT NULL COMMENT 'user_group.id',
    `account_id`    BIGINT      NOT NULL COMMENT 'account.id',
    `create_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_group_member` (`user_group_id`, `account_id`),
    KEY `idx_user_group_member_account` (`account_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='User group membership';

CREATE TABLE IF NOT EXISTS `user_group_model_group` (
    `id`             BIGINT      NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `user_group_id`  BIGINT      NOT NULL COMMENT 'user_group.id',
    `model_group_id` BIGINT      NOT NULL COMMENT 'model_group.id',
    `create_time`    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_group_model_group` (`user_group_id`, `model_group_id`),
    KEY `idx_user_group_model_group_group` (`model_group_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Model group authorization for user groups';
