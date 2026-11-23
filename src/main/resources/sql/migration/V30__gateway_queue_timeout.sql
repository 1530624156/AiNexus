-- V30: make global gateway queue waiting time an explicit runtime control.
USE ainexus;

ALTER TABLE `gateway_runtime_setting`
    ADD COLUMN `queue_timeout_seconds` INT NOT NULL DEFAULT 60 COMMENT 'Maximum global-concurrency queue wait; request is rejected after timeout';
