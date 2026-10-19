-- V6: allow a channel to record multiple supported upstream protocols.
-- `protocol` remains the single production primary protocol used by routing.
USE ainexus;

ALTER TABLE `channel`
    ADD COLUMN `supported_protocols` TEXT NULL COMMENT 'JSON array of supported protocol values; protocol is the primary production selection' AFTER `protocol`;

UPDATE `channel`
SET `supported_protocols` = CONCAT('["', COALESCE(NULLIF(`protocol`, ''), 'openai-completions'), '"]')
WHERE `supported_protocols` IS NULL OR `supported_protocols` = '';
