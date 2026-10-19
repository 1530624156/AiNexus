-- V13: persisted gateway runtime settings.
USE ainexus;

CREATE TABLE IF NOT EXISTS `gateway_runtime_setting` (
    `id` BIGINT NOT NULL COMMENT 'Singleton row, always 1',
    `access_url` VARCHAR(512) NOT NULL COMMENT 'Public OpenAI Base URL ending with /v1',
    `protocol_conversion_enabled` TINYINT NOT NULL DEFAULT 1 COMMENT 'Enable Responses/Anthropic fallback conversion',
    `max_concurrent` INT NOT NULL DEFAULT 0 COMMENT 'Global in-flight limit; 0=unlimited',
    `queue_capacity` INT NOT NULL DEFAULT 0 COMMENT 'Waiting queue capacity; 0=reject while full',
    `non_stream_timeout_seconds` INT NOT NULL DEFAULT 180 COMMENT 'Non-stream upstream timeout',
    `stream_idle_timeout_seconds` INT NOT NULL DEFAULT 0 COMMENT 'Streaming idle timeout; 0=no idle timeout',
    `max_payload_mb` INT NOT NULL DEFAULT 16 COMMENT 'Maximum request body size in MiB',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Live gateway runtime settings';

INSERT INTO `gateway_runtime_setting` (`id`, `access_url`, `protocol_conversion_enabled`, `max_concurrent`, `queue_capacity`, `non_stream_timeout_seconds`, `stream_idle_timeout_seconds`, `max_payload_mb`)
VALUES (1, 'http://localhost:8080/v1', 1, 0, 0, 180, 0, 16)
ON DUPLICATE KEY UPDATE `id` = `id`;
