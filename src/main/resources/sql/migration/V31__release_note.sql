-- V31: Administrator-managed release notes published to console users.
USE ainexus;

CREATE TABLE IF NOT EXISTS `release_note` (
    `id`           BIGINT         NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `version`      VARCHAR(64)    NOT NULL COMMENT 'Release version, for example v0.2.0',
    `title`        VARCHAR(160)   NOT NULL COMMENT 'Release title',
    `content`      VARCHAR(10000) NOT NULL COMMENT 'Release details in plain text',
    `status`       VARCHAR(16)    NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT or PUBLISHED',
    `published_at` DATETIME       NULL COMMENT 'First publication time',
    `created_by`   VARCHAR(128)   NULL COMMENT 'Administrator username snapshot',
    `create_time`  DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`  DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_release_note_status_published` (`status`, `published_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Console release notes';
