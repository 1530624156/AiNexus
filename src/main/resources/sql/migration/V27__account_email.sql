-- V27: required email address for every console account.
USE ainexus;

ALTER TABLE `account`
    ADD COLUMN `email` VARCHAR(254) NOT NULL DEFAULT 'unknown@localhost' COMMENT 'User-maintained contact email' AFTER `username`;
