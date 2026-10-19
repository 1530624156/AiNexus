-- AiNexus AI Gateway final schema (MySQL 5.7 compatible)
--
-- Core design:
--   channel       : provider/channel-level connection and routing configuration
--   channel_model : models bound to a channel (one channel can bind many models)
--
-- Run this file for a NEW database. Existing databases created by the first version
-- must run sql/migration/V2__channel_model_binding.sql once instead.
CREATE DATABASE IF NOT EXISTS ainexus DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE ainexus;

CREATE TABLE IF NOT EXISTS `channel` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `name`               VARCHAR(128) NOT NULL COMMENT 'Human-readable channel name, e.g. BigModel-ZhiPu-Primary',
    `provider`           VARCHAR(64)  NOT NULL DEFAULT 'custom' COMMENT 'Provider code: zhipu/openai/deepseek/qwen/custom',
    `protocol`           VARCHAR(32)  NOT NULL DEFAULT 'openai-completions' COMMENT 'Primary production protocol: openai-completions/openai-responses/anthropic-messages',
    `supported_protocols` TEXT         NULL COMMENT 'JSON array of supported protocols; must include protocol',
    `protocol_config`    TEXT         NULL COMMENT 'JSON protocol override: requestPath/authHeader/authPrefix/headers',
    `base_url`           VARCHAR(512) NOT NULL COMMENT 'Provider Base URL',
    `api_key`            VARCHAR(512) NOT NULL COMMENT 'Provider API key, must be encrypted in production',
    `priority`           INT          NOT NULL DEFAULT 0 COMMENT 'Higher value wins; only same priority competes by weight',
    `weight`             INT          NOT NULL DEFAULT 1 COMMENT 'Weighted routing value within the same priority',
    `max_concurrent`     INT          NOT NULL DEFAULT 0 COMMENT 'Maximum in-flight requests; 0 means unlimited',
    `failure_count`      INT          NOT NULL DEFAULT 0 COMMENT 'Cumulative upstream failure count',
    `default_test_model` VARCHAR(128) NULL COMMENT 'Default public model used by management/test UI',
    `status`             TINYINT      NOT NULL DEFAULT 1 COMMENT '1=enabled, 0=disabled',
    `deleted`            TINYINT      NOT NULL DEFAULT 0 COMMENT 'Logical delete',
    `remark`             VARCHAR(512) NULL COMMENT 'Operator note',
    `create_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_channel_name` (`name`),
    KEY `idx_channel_enabled_priority` (`status`, `deleted`, `priority`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI upstream channel configuration';

CREATE TABLE IF NOT EXISTS `gateway_token` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `token`             VARCHAR(512) NOT NULL COMMENT 'Internal Bearer Token; encrypt at rest in production',
    `app_name`          VARCHAR(128) NOT NULL COMMENT 'Owning internal application',
    `rate_limit_config` TEXT         NULL COMMENT 'Optional JSON rate-limit configuration',
    `status`            TINYINT      NOT NULL DEFAULT 1 COMMENT '1=enabled, 0=disabled',
    `deleted`           TINYINT      NOT NULL DEFAULT 0 COMMENT 'Logical delete',
    `remark`            VARCHAR(512) NULL COMMENT 'Operator note',
    `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_gateway_token` (`token`),
    KEY `idx_token_enabled` (`status`, `deleted`),
    KEY `idx_token_app` (`app_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Internal application tokens';

CREATE TABLE IF NOT EXISTS `channel_model` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `channel_id`          BIGINT       NOT NULL COMMENT 'channel.id',
    `model_name`          VARCHAR(128) NOT NULL COMMENT 'Public model name accepted by gateway',
    `upstream_model_name` VARCHAR(128) NULL COMMENT 'Actual provider model name; null means same as model_name',
    `status`              TINYINT      NOT NULL DEFAULT 1 COMMENT '1=enabled, 0=disabled',
    `deleted`             TINYINT      NOT NULL DEFAULT 0 COMMENT 'Logical delete',
    `remark`              VARCHAR(512) NULL COMMENT 'Operator note',
    `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_channel_model` (`channel_id`, `model_name`),
    KEY `idx_model_enabled` (`model_name`, `status`, `deleted`),
    KEY `idx_channel_enabled` (`channel_id`, `status`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Models bound to upstream channels';

CREATE TABLE IF NOT EXISTS `request_audit_log` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `request_id`     VARCHAR(64)  NULL COMMENT 'Gateway request correlation id',
    `app_name`          VARCHAR(128)  NULL COMMENT 'Authenticated internal application name',
    `api_key_name`      VARCHAR(128)  NULL COMMENT 'API key/app label snapshot',
    `account_id`        BIGINT        NULL COMMENT 'Console account snapshot',
    `user_name`         VARCHAR(128)  NULL COMMENT 'Console username snapshot',
    `request_model`     VARCHAR(128)  NULL COMMENT 'Requested public model',
    `upstream_model`    VARCHAR(128)  NULL COMMENT 'Actual upstream model snapshot',
    `target_channel`    VARCHAR(128)  NULL COMMENT 'Final attempted upstream channel',
    `provider`          VARCHAR(64)   NULL COMMENT 'Upstream provider snapshot',
    `upstream_account`  VARCHAR(128)  NULL COMMENT 'Selected upstream channel snapshot',
    `api_type`          VARCHAR(64)   NULL COMMENT 'Gateway API type',
    `stream`            TINYINT       NOT NULL DEFAULT 0 COMMENT '1=streaming request',
    `upstream_endpoint` VARCHAR(512)  NULL COMMENT 'Upstream base URL snapshot',
    `protocol_path`     VARCHAR(256)  NULL COMMENT 'Actual upstream request path snapshot',
    `model_group_names` VARCHAR(1000) NULL COMMENT 'Model group snapshot',
    `user_group_names`  VARCHAR(1000) NULL COMMENT 'User group snapshot',
    `prompt_tokens`     INT           NULL COMMENT 'Legacy estimated prompt tokens',
    `input_tokens`      BIGINT        NOT NULL DEFAULT 0 COMMENT 'Input tokens',
    `output_tokens`     BIGINT        NOT NULL DEFAULT 0 COMMENT 'Output tokens',
    `total_tokens`      BIGINT        NOT NULL DEFAULT 0 COMMENT 'Total tokens',
    `cache_tokens`      BIGINT        NOT NULL DEFAULT 0 COMMENT 'Cached input tokens',
    `status_code`       INT           NOT NULL COMMENT 'Gateway response HTTP status',
    `upstream_status_code` INT        NULL COMMENT 'Upstream HTTP status code',
    `latency_ms`     BIGINT       NOT NULL COMMENT 'End-to-end gateway latency in milliseconds',
    `first_token_ms` BIGINT       NULL COMMENT 'Time to first upstream response byte',
    `queue_ms`       BIGINT       NOT NULL DEFAULT 0 COMMENT 'Gateway queue time',
    `upstream_latency_ms` BIGINT  NULL COMMENT 'Upstream accumulated latency',
    `error_msg`      VARCHAR(1000) NULL COMMENT 'Truncated error summary; no request body or keys',
    `error_code`     VARCHAR(128)  NULL COMMENT 'Gateway or upstream error code',
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_audit_request_id` (`request_id`),
    KEY `idx_audit_request_id` (`request_id`),
    KEY `idx_audit_created` (`create_time`),
    KEY `idx_audit_app_created` (`app_name`, `create_time`),
    KEY `idx_audit_channel_created` (`target_channel`, `create_time`),
    KEY `idx_audit_status_created` (`status_code`, `create_time`),
    KEY `idx_audit_user_created` (`user_name`, `create_time`),
    KEY `idx_audit_provider_created` (`provider`, `create_time`),
    KEY `idx_audit_api_created` (`api_type`, `create_time`),
    KEY `idx_audit_model_created` (`request_model`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Asynchronous gateway request audit log';

-- Development bootstrap Token. Production should replace it using your secret-management process.
INSERT INTO `gateway_token` (`token`, `app_name`, `status`, `remark`)
SELECT 'sk-ainexus-dev-team-a', 'dev-team-a', 1, 'Bootstrap development token'
WHERE NOT EXISTS (SELECT 1 FROM `gateway_token` WHERE `token` = 'sk-ainexus-dev-team-a');

INSERT INTO `gateway_token` (`token`, `app_name`, `status`, `remark`)
SELECT 'sk-ainexus-dev-team-b', 'dev-team-b', 1, 'Bootstrap development token'
WHERE NOT EXISTS (SELECT 1 FROM `gateway_token` WHERE `token` = 'sk-ainexus-dev-team-b');

-- Example: one BigModel (ZhiPu) channel binds multiple public models.
-- Replace sk-xxxx before enabling it in production.
INSERT INTO `channel` (`name`, `provider`, `protocol`, `base_url`, `api_key`, `priority`, `weight`, `max_concurrent`, `default_test_model`, `status`, `remark`)
SELECT 'BigModel-ZhiPu', 'zhipu', 'openai-completions', 'https://open.bigmodel.cn/api/paas/v4', 'sk-xxxx', 100, 10, 50, 'GLM-5.3', 0,
       'Example only. Replace API key then set status=1.'
WHERE NOT EXISTS (SELECT 1 FROM `channel` WHERE `name` = 'BigModel-ZhiPu');

INSERT INTO `channel_model` (`channel_id`, `model_name`, `upstream_model_name`, `status`, `remark`)
SELECT c.id, model_binding.model_name, NULL, 1, 'BigModel-ZhiPu example binding'
FROM `channel` c
JOIN (
    SELECT 'GLM-5.3' AS model_name
    UNION ALL SELECT 'GLM-5.3-FLASH'
    UNION ALL SELECT 'GLM-5.2'
) model_binding
WHERE c.name = 'BigModel-ZhiPu'
  AND NOT EXISTS (
      SELECT 1 FROM `channel_model` cm
      WHERE cm.channel_id = c.id AND cm.model_name = model_binding.model_name
  );

-- Operational example:
-- INSERT INTO channel (name, provider, protocol, protocol_config, base_url, api_key, priority, weight, max_concurrent, default_test_model)
-- VALUES ('BigModel-Primary', 'zhipu', 'openai', '{"requestPath":"/chat/completions"}',
--         'https://open.bigmodel.cn/api/paas/v4', 'sk-real-key', 100, 10, 50, 'GLM-5.3');
-- INSERT INTO channel_model (channel_id, model_name) VALUES
--   (LAST_INSERT_ID(), 'GLM-5.3'),
--   (LAST_INSERT_ID(), 'GLM-5.3-FLASH'),
--   (LAST_INSERT_ID(), 'GLM-5.2');
