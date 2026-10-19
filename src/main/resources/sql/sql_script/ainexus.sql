/*
 Navicat Premium Dump SQL

 Source Server         : MySQL5.7
 Source Server Type    : MySQL
 Source Server Version : 50709 (5.7.9-log)
 Source Host           : localhost:3306
 Source Schema         : ainexus

 Target Server Type    : MySQL
 Target Server Version : 50709 (5.7.9-log)
 File Encoding         : 65001

 Date: 19/10/2026 15:46:58
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for account
-- ----------------------------
DROP TABLE IF EXISTS `account`;
CREATE TABLE `account`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `username` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'Unique login name',
  `password_hash` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'PBKDF2 password hash',
  `role` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'USER' COMMENT 'ADMIN or USER',
  `status` tinyint(4) NOT NULL DEFAULT 1 COMMENT '1=enabled, 0=disabled',
  `remark` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'Operator note',
  `deleted` tinyint(4) NOT NULL DEFAULT 0 COMMENT 'Logical delete',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_account_username`(`username`) USING BTREE,
  INDEX `idx_account_role_status`(`role`, `status`, `deleted`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 2 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'Console user accounts' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of account
-- ----------------------------
INSERT INTO `account` VALUES (1, 'admin', 'pbkdf2$210000$GjqziW1g8Uos0CzTUTQoAA==$J7uQwHFjuFonxeO8/mVzVtBWQc5U5hhO6rRPhUOHEYA=', 'ADMIN', 1, 'Bootstrap administrator', 0, '2026-10-19 15:42:52', '2026-10-19 15:42:52');

-- ----------------------------
-- Table structure for channel
-- ----------------------------
DROP TABLE IF EXISTS `channel`;
CREATE TABLE `channel`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'Human-readable channel name, e.g. BigModel-ZhiPu-Primary',
  `provider` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'custom' COMMENT 'Provider code: zhipu/openai/deepseek/qwen/custom',
  `protocol` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'openai-completions' COMMENT 'Primary production protocol: openai-completions/openai-responses/anthropic-messages',
  `supported_protocols` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT 'JSON array of supported protocols; must include protocol',
  `protocol_config` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT 'JSON protocol override: requestPath/authHeader/authPrefix/headers',
  `base_url` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'Provider Base URL',
  `api_key` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'Provider API key, must be encrypted in production',
  `priority` int(11) NOT NULL DEFAULT 0 COMMENT 'Higher value wins; only same priority competes by weight',
  `weight` int(11) NOT NULL DEFAULT 1 COMMENT 'Weighted routing value within the same priority',
  `max_concurrent` int(11) NOT NULL DEFAULT 0 COMMENT 'Maximum in-flight requests; 0 means unlimited',
  `failure_count` int(11) NOT NULL DEFAULT 0 COMMENT 'Cumulative upstream failure count',
  `default_test_model` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'Default public model used by management/test UI',
  `status` tinyint(4) NOT NULL DEFAULT 1 COMMENT '1=enabled, 0=disabled',
  `deleted` tinyint(4) NOT NULL DEFAULT 0 COMMENT 'Logical delete',
  `remark` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'Operator note',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_channel_name`(`name`) USING BTREE,
  INDEX `idx_channel_enabled_priority`(`status`, `deleted`, `priority`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 2 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'AI upstream channel configuration' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of channel
-- ----------------------------

-- ----------------------------
-- Table structure for channel_model
-- ----------------------------
DROP TABLE IF EXISTS `channel_model`;
CREATE TABLE `channel_model`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `channel_id` bigint(20) NOT NULL COMMENT 'channel.id',
  `model_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'Public model name accepted by gateway',
  `upstream_model_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'Actual provider model name; null means same as model_name',
  `status` tinyint(4) NOT NULL DEFAULT 1 COMMENT '1=enabled, 0=disabled',
  `deleted` tinyint(4) NOT NULL DEFAULT 0 COMMENT 'Logical delete',
  `remark` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'Operator note',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_channel_model`(`channel_id`, `model_name`) USING BTREE,
  INDEX `idx_model_enabled`(`model_name`, `status`, `deleted`) USING BTREE,
  INDEX `idx_channel_enabled`(`channel_id`, `status`, `deleted`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 4 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'Models bound to upstream channels' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of channel_model
-- ----------------------------

-- ----------------------------
-- Table structure for gateway_runtime_setting
-- ----------------------------
DROP TABLE IF EXISTS `gateway_runtime_setting`;
CREATE TABLE `gateway_runtime_setting`  (
  `id` bigint(20) NOT NULL COMMENT 'Singleton row, always 1',
  `access_url` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'Public OpenAI Base URL ending with /v1',
  `protocol_conversion_enabled` tinyint(4) NOT NULL DEFAULT 1 COMMENT 'Enable Responses/Anthropic fallback conversion',
  `max_concurrent` int(11) NOT NULL DEFAULT 0 COMMENT 'Global in-flight limit; 0=unlimited',
  `queue_capacity` int(11) NOT NULL DEFAULT 0 COMMENT 'Waiting queue capacity; 0=reject while full',
  `non_stream_timeout_seconds` int(11) NOT NULL DEFAULT 180 COMMENT 'Non-stream upstream timeout',
  `stream_idle_timeout_seconds` int(11) NOT NULL DEFAULT 0 COMMENT 'Streaming idle timeout; 0=no idle timeout',
  `max_payload_mb` int(11) NOT NULL DEFAULT 16 COMMENT 'Maximum request body size in MiB',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'Live gateway runtime settings' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of gateway_runtime_setting
-- ----------------------------
INSERT INTO `gateway_runtime_setting` VALUES (1, 'http://localhost:8080/v1', 1, 0, 0, 180, 0, 16, '2026-10-19 15:41:24');

-- ----------------------------
-- Table structure for gateway_token
-- ----------------------------
DROP TABLE IF EXISTS `gateway_token`;
CREATE TABLE `gateway_token`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `token` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'Internal Bearer Token; encrypt at rest in production',
  `app_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'Owning internal application',
  `account_id` bigint(20) NULL DEFAULT NULL COMMENT 'Owning console account for USER portal API keys',
  `rate_limit_config` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT 'Optional JSON rate-limit configuration',
  `status` tinyint(4) NOT NULL DEFAULT 1 COMMENT '1=enabled, 0=disabled',
  `deleted` tinyint(4) NOT NULL DEFAULT 0 COMMENT 'Logical delete',
  `remark` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'Operator note',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_gateway_token`(`token`) USING BTREE,
  INDEX `idx_token_enabled`(`status`, `deleted`) USING BTREE,
  INDEX `idx_token_app`(`app_name`) USING BTREE,
  INDEX `idx_token_account_enabled`(`account_id`, `status`, `deleted`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 3 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'Internal application tokens' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of gateway_token
-- ----------------------------

-- ----------------------------
-- Table structure for model_group
-- ----------------------------
DROP TABLE IF EXISTS `model_group`;
CREATE TABLE `model_group`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'Unique model group name',
  `remark` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'Operator note',
  `deleted` tinyint(4) NOT NULL DEFAULT 0 COMMENT 'Logical delete',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_model_group_name`(`name`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'Model groups' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of model_group
-- ----------------------------

-- ----------------------------
-- Table structure for model_group_model
-- ----------------------------
DROP TABLE IF EXISTS `model_group_model`;
CREATE TABLE `model_group_model`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `model_group_id` bigint(20) NOT NULL COMMENT 'model_group.id',
  `model_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'Public gateway model name',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_group_model`(`model_group_id`, `model_name`) USING BTREE,
  INDEX `idx_group_model_group`(`model_group_id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'Models assigned to a model group' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of model_group_model
-- ----------------------------

-- ----------------------------
-- Table structure for request_audit_log
-- ----------------------------
DROP TABLE IF EXISTS `request_audit_log`;
CREATE TABLE `request_audit_log`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `request_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'Gateway request correlation id',
  `app_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'Authenticated internal application name',
  `api_key_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'API key/app label snapshot',
  `account_id` bigint(20) NULL DEFAULT NULL COMMENT 'Console account snapshot',
  `user_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'Console username snapshot',
  `request_model` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'Requested public model',
  `upstream_model` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'Actual upstream model snapshot',
  `target_channel` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'Final attempted upstream channel',
  `provider` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'Upstream provider snapshot',
  `upstream_account` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'Selected upstream channel snapshot',
  `api_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'Gateway API type',
  `stream` tinyint(4) NOT NULL DEFAULT 0 COMMENT '1=streaming request',
  `upstream_endpoint` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'Upstream base URL snapshot',
  `protocol_path` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'Actual upstream request path snapshot',
  `model_group_names` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'Model group snapshot',
  `user_group_names` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'User group snapshot',
  `prompt_tokens` int(11) NULL DEFAULT NULL COMMENT 'Legacy estimated prompt tokens',
  `input_tokens` bigint(20) NOT NULL DEFAULT 0 COMMENT 'Input tokens',
  `output_tokens` bigint(20) NOT NULL DEFAULT 0 COMMENT 'Output tokens',
  `total_tokens` bigint(20) NOT NULL DEFAULT 0 COMMENT 'Total tokens',
  `cache_tokens` bigint(20) NOT NULL DEFAULT 0 COMMENT 'Cached input tokens',
  `status_code` int(11) NOT NULL COMMENT 'Gateway response HTTP status',
  `upstream_status_code` int(11) NULL DEFAULT NULL COMMENT 'Upstream HTTP status code',
  `latency_ms` bigint(20) NOT NULL COMMENT 'End-to-end gateway latency in milliseconds',
  `first_token_ms` bigint(20) NULL DEFAULT NULL COMMENT 'Time to first upstream response byte',
  `queue_ms` bigint(20) NOT NULL DEFAULT 0 COMMENT 'Gateway queue time',
  `upstream_latency_ms` bigint(20) NULL DEFAULT NULL COMMENT 'Upstream accumulated latency',
  `error_msg` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'Truncated error summary; no request body or keys',
  `error_code` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'Gateway or upstream error code',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_audit_request_id`(`request_id`) USING BTREE,
  INDEX `idx_audit_request_id`(`request_id`) USING BTREE,
  INDEX `idx_audit_created`(`create_time`) USING BTREE,
  INDEX `idx_audit_app_created`(`app_name`, `create_time`) USING BTREE,
  INDEX `idx_audit_channel_created`(`target_channel`, `create_time`) USING BTREE,
  INDEX `idx_audit_status_created`(`status_code`, `create_time`) USING BTREE,
  INDEX `idx_audit_user_created`(`user_name`, `create_time`) USING BTREE,
  INDEX `idx_audit_provider_created`(`provider`, `create_time`) USING BTREE,
  INDEX `idx_audit_api_created`(`api_type`, `create_time`) USING BTREE,
  INDEX `idx_audit_model_created`(`request_model`, `create_time`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'Asynchronous gateway request audit log' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of request_audit_log
-- ----------------------------

-- ----------------------------
-- Table structure for user_group
-- ----------------------------
DROP TABLE IF EXISTS `user_group`;
CREATE TABLE `user_group`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'Unique group name',
  `max_concurrent` int(11) NOT NULL DEFAULT 0 COMMENT 'Group max concurrent requests; 0=unlimited',
  `api_key_max_concurrent` int(11) NOT NULL DEFAULT 0 COMMENT 'Max concurrent requests per API key; 0=unlimited',
  `token_quota` bigint(20) NOT NULL DEFAULT 0 COMMENT 'Group max token quota per period; 0=unlimited',
  `quota_period` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'MONTH' COMMENT 'DAY/WEEK/MONTH/YEAR',
  `member_token_quota` bigint(20) NOT NULL DEFAULT 0 COMMENT 'Per-member max token quota per period; 0=unlimited',
  `member_max_concurrent` int(11) NOT NULL DEFAULT 0 COMMENT 'Per-member max concurrent requests; 0=unlimited',
  `remark` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'Operator note',
  `deleted` tinyint(4) NOT NULL DEFAULT 0 COMMENT 'Logical delete',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_user_group_name`(`name`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'User access groups and quotas' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of user_group
-- ----------------------------

-- ----------------------------
-- Table structure for user_group_member
-- ----------------------------
DROP TABLE IF EXISTS `user_group_member`;
CREATE TABLE `user_group_member`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `user_group_id` bigint(20) NOT NULL COMMENT 'user_group.id',
  `account_id` bigint(20) NOT NULL COMMENT 'account.id',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_user_group_member`(`user_group_id`, `account_id`) USING BTREE,
  INDEX `idx_user_group_member_account`(`account_id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'User group membership' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of user_group_member
-- ----------------------------

-- ----------------------------
-- Table structure for user_group_model_group
-- ----------------------------
DROP TABLE IF EXISTS `user_group_model_group`;
CREATE TABLE `user_group_model_group`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `user_group_id` bigint(20) NOT NULL COMMENT 'user_group.id',
  `model_group_id` bigint(20) NOT NULL COMMENT 'model_group.id',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_user_group_model_group`(`user_group_id`, `model_group_id`) USING BTREE,
  INDEX `idx_user_group_model_group_group`(`model_group_id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'Model group authorization for user groups' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of user_group_model_group
-- ----------------------------

SET FOREIGN_KEY_CHECKS = 1;
