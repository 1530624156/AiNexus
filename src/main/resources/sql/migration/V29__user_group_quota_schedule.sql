-- V29: optional time-based daily token quota increments for user groups.
USE ainexus;

ALTER TABLE `user_group`
    ADD COLUMN `quota_schedule` TEXT NULL COMMENT 'JSON daily quota increments activated at HH:mm';
