-- V16: normalize audit token accounting to non-cached input + output + cache = total.
USE ainexus;

-- Historical input_tokens included cache_tokens for providers reporting full prompt_tokens.
-- Cache creation was also added into input_tokens for Anthropic; only cache reads are displayed separately.
UPDATE `request_audit_log`
SET `total_tokens` = GREATEST(0, COALESCE(`input_tokens`, 0) - COALESCE(`cache_tokens`, 0))
        + COALESCE(`output_tokens`, 0) + COALESCE(`cache_tokens`, 0),
    `input_tokens` = GREATEST(0, COALESCE(`input_tokens`, 0) - COALESCE(`cache_tokens`, 0))
WHERE COALESCE(`cache_tokens`, 0) > 0;

-- Ensure the identity holds for all historical rows, including rows without cache usage.
UPDATE `request_audit_log`
SET `total_tokens` = COALESCE(`input_tokens`, 0) + COALESCE(`output_tokens`, 0) + COALESCE(`cache_tokens`, 0)
WHERE COALESCE(`total_tokens`, 0) <> COALESCE(`input_tokens`, 0) + COALESCE(`output_tokens`, 0) + COALESCE(`cache_tokens`, 0);
