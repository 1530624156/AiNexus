-- V18: allow administrators to require GitHub OAuth sign-in for USER accounts.
USE ainexus;

ALTER TABLE `gateway_runtime_setting`
    ADD COLUMN `github_login_only` TINYINT NOT NULL DEFAULT 0 COMMENT 'Require GitHub OAuth sign-in for USER accounts';
