-- V17: a GitHub identity may map to exactly one system account for GitHub sign-in.
USE ainexus;

ALTER TABLE `account_github_binding`
    ADD UNIQUE KEY `uk_github_login` (`github_login`);
