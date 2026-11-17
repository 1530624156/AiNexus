-- V23: User feedback submitted from the console, plus one administrator reply per feedback.
USE ainexus;

CREATE TABLE IF NOT EXISTS `user_feedback` (
    `id`            BIGINT        NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `account_id`    BIGINT        NOT NULL COMMENT 'Submitter console account id',
    `username`      VARCHAR(128)  NOT NULL COMMENT 'Submitter username snapshot',
    `category`      VARCHAR(32)   NOT NULL DEFAULT 'OTHER' COMMENT 'SUGGESTION, BUG, PERFORMANCE or OTHER',
    `content`       VARCHAR(2000) NOT NULL COMMENT 'Feedback content',
    `contact`       VARCHAR(128)  NULL COMMENT 'Optional contact left by the submitter',
    `status`        VARCHAR(24)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING or REPLIED',
    `reply_content` VARCHAR(2000) NULL COMMENT 'Administrator reply; latest reply wins',
    `replied_by`    VARCHAR(128)  NULL COMMENT 'Administrator username snapshot',
    `replied_at`    DATETIME      NULL,
    `create_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_feedback_account_created` (`account_id`, `create_time`),
    KEY `idx_feedback_status_created` (`status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Console user feedback and administrator replies';