-- V12: audit detail fields for request trace list and drawer.
USE ainexus;

ALTER TABLE `request_audit_log`
    ADD COLUMN `request_id` VARCHAR(64) NULL COMMENT 'Gateway request correlation id' AFTER `id`,
    ADD COLUMN `api_key_name` VARCHAR(128) NULL COMMENT 'API key/app label snapshot' AFTER `app_name`,
    ADD COLUMN `upstream_model` VARCHAR(128) NULL COMMENT 'Actual upstream model snapshot' AFTER `request_model`,
    ADD COLUMN `stream` TINYINT NOT NULL DEFAULT 0 COMMENT '1=streaming request' AFTER `api_type`,
    ADD COLUMN `upstream_endpoint` VARCHAR(512) NULL COMMENT 'Upstream base URL snapshot' AFTER `upstream_account`,
    ADD COLUMN `protocol_path` VARCHAR(256) NULL COMMENT 'Actual upstream request path snapshot' AFTER `upstream_endpoint`,
    ADD COLUMN `upstream_status_code` INT NULL COMMENT 'Upstream HTTP status code' AFTER `status_code`,
    ADD COLUMN `first_token_ms` BIGINT NULL COMMENT 'Time to first upstream response byte' AFTER `latency_ms`,
    ADD COLUMN `queue_ms` BIGINT NOT NULL DEFAULT 0 COMMENT 'Gateway queue time' AFTER `first_token_ms`,
    ADD COLUMN `upstream_latency_ms` BIGINT NULL COMMENT 'Upstream accumulated latency' AFTER `queue_ms`,
    ADD COLUMN `error_code` VARCHAR(128) NULL COMMENT 'Gateway/upstream error code' AFTER `error_msg`,
    ADD UNIQUE KEY `uk_audit_request_id` (`request_id`),
    ADD KEY `idx_audit_request_id` (`request_id`);
