-- V9: reusable model groups for future user/group access assignment.
USE ainexus;

CREATE TABLE IF NOT EXISTS `model_group` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `name`        VARCHAR(128) NOT NULL COMMENT 'Unique model group name',
    `remark`      VARCHAR(512) NULL COMMENT 'Operator note',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT 'Logical delete',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_model_group_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Model groups';

CREATE TABLE IF NOT EXISTS `model_group_model` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `model_group_id` BIGINT       NOT NULL COMMENT 'model_group.id',
    `model_name`     VARCHAR(128) NOT NULL COMMENT 'Public gateway model name',
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_group_model` (`model_group_id`, `model_name`),
    KEY `idx_group_model_group` (`model_group_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Models assigned to a model group';
