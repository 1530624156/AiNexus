-- V22: persist administrator-configured named public OpenAI Base URLs.
USE ainexus;

ALTER TABLE `gateway_runtime_setting`
    ADD COLUMN `access_urls` TEXT NULL COMMENT 'JSON list of named public OpenAI Base URLs; access_url remains the default';
