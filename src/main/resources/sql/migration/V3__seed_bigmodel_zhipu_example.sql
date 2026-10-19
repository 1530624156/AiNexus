-- V3: seed a disabled BigModel/ZhiPu channel example with multiple model bindings.
-- Safe to run repeatedly. Replace sk-xxxx and set channel.status=1 before use.
USE ainexus;

INSERT INTO `channel` (`name`, `provider`, `protocol`, `protocol_config`, `base_url`, `api_key`, `priority`, `weight`, `max_concurrent`, `default_test_model`, `status`, `remark`)
SELECT 'BigModel-ZhiPu', 'zhipu', 'openai', NULL,
       'https://open.bigmodel.cn/api/paas/v4', 'sk-xxxx',
       100, 10, 50, 'GLM-5.3', 0,
       'Disabled example. Replace api_key, review protocol_config, then set status=1.'
WHERE NOT EXISTS (SELECT 1 FROM `channel` WHERE `name` = 'BigModel-ZhiPu');

INSERT INTO `channel_model` (`channel_id`, `model_name`, `upstream_model_name`, `status`, `remark`)
SELECT c.id, binding.model_name, NULL, 1, 'BigModel-ZhiPu example binding'
FROM `channel` c
JOIN (
    SELECT 'GLM-5.3' AS model_name
    UNION ALL SELECT 'GLM-5.3-FLASH'
    UNION ALL SELECT 'GLM-5.2'
) binding
WHERE c.name = 'BigModel-ZhiPu'
  AND NOT EXISTS (
      SELECT 1 FROM `channel_model` cm
      WHERE cm.channel_id = c.id AND cm.model_name = binding.model_name
  );
