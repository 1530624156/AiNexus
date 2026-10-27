-- V19: allowed call window (time-of-day restriction) for user groups.
-- Both columns NULL/empty => no time restriction (24h allowed).
-- allow_time_end <= allow_time_start => the window crosses midnight (e.g. 22:00 - 06:00).
USE ainexus;

ALTER TABLE `user_group`
    ADD COLUMN `allow_time_start` VARCHAR(5) NULL COMMENT 'Allowed call window start HH:mm; NULL = no restriction' AFTER `member_max_concurrent`,
    ADD COLUMN `allow_time_end`   VARCHAR(5) NULL COMMENT 'Allowed call window end HH:mm; end<=start crosses midnight' AFTER `allow_time_start`;
