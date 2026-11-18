-- V28: persisted switch for bypassing user quota and user-group concurrency limits.
USE ainexus;

ALTER TABLE `gateway_runtime_setting`
    ADD COLUMN `crazy_mode_enabled` TINYINT NOT NULL DEFAULT 0 COMMENT 'Bypass user token quota and user-group concurrency limits';
