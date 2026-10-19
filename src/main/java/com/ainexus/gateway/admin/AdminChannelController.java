package com.ainexus.gateway.admin;

import com.ainexus.gateway.common.BizException;
import com.ainexus.gateway.entity.Channel;
import com.ainexus.gateway.entity.ChannelModel;
import com.ainexus.gateway.mapper.ChannelMapper;
import com.ainexus.gateway.route.ChannelProtocol;
import com.ainexus.gateway.mapper.ChannelModelMapper;
import com.ainexus.gateway.service.RouteCacheService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** 管理端渠道与模型绑定 CRUD。 */
@RestController
@RequestMapping("/api/admin/channels")
@RequiredArgsConstructor
public class AdminChannelController {

    private final ChannelMapper channelMapper;
    private final ChannelModelMapper channelModelMapper;
    private final RouteCacheService routeCacheService;
    private final UpstreamModelDiscoveryService upstreamModelDiscoveryService;
    private final ChannelTestService channelTestService;
    private final ObjectMapper objectMapper;

    @GetMapping
    public List<ChannelView> list() {
        return channelMapper.selectList(new LambdaQueryWrapper<Channel>().orderByDesc(Channel::getPriority).orderByAsc(Channel::getId))
                .stream().map(this::toView).toList();
    }

    @GetMapping("/{id}")
    public ChannelView get(@PathVariable Long id) {
        return toView(requiredChannel(id));
    }

    /** 根据当前表单的上游地址与临时 API Key 获取可选模型；不保存任何表单内容。 */
    @PostMapping("/discover-models")
    public List<UpstreamModelDiscoveryService.DiscoveredModel> discoverModels(
            @Valid @RequestBody ModelDiscoveryRequest request) {
        return upstreamModelDiscoveryService.discover(request.baseUrl(), request.apiKey(), request.protocol(), request.protocolConfig());
    }

    /** 使用已保存渠道的服务端密钥发现模型，绝不向浏览器返回 API Key。 */
    @PostMapping("/{id}/discover-models")
    public List<UpstreamModelDiscoveryService.DiscoveredModel> discoverSavedChannelModels(@PathVariable Long id) {
        Channel channel = requiredChannel(id);
        return upstreamModelDiscoveryService.discover(channel.getBaseUrl(), channel.getApiKey(),
                channel.getProtocol(), channel.getProtocolConfig());
    }

    /** 对当前已保存渠道的一个或多个绑定模型发起实际非流式对话测试。 */
    @PostMapping("/{id}/test")
    public List<ChannelTestService.TestResult> test(@PathVariable Long id,
                                                     @RequestBody(required = false) ChannelTestRequest request) {
        Channel channel = requiredChannel(id);
        List<ChannelModel> bindings = channelModelMapper.selectList(new LambdaQueryWrapper<ChannelModel>()
                .eq(ChannelModel::getChannelId, id).eq(ChannelModel::getStatus, 1).orderByAsc(ChannelModel::getModelName));
        try {
            return channelTestService.test(channel, bindings, request == null ? null : request.modelNames(),
                    request == null ? null : request.prompt());
        } catch (IllegalArgumentException e) {
            throw BizException.badRequest("invalid_channel_test", e.getMessage());
        }
    }

    @PostMapping
    public ChannelView create(@Valid @RequestBody ChannelUpsertRequest request) {
        Channel channel = toEntity(request, null);
        channelMapper.insert(channel);
        replaceModels(channel.getId(), request.models());
        routeCacheService.refresh();
        return toView(requiredChannel(channel.getId()));
    }

    @PutMapping("/{id}")
    public ChannelView update(@PathVariable Long id, @Valid @RequestBody ChannelUpsertRequest request) {
        Channel existing = requiredChannel(id);
        Channel channel = toEntity(request, existing);
        channel.setId(id);
        channelMapper.updateById(channel);
        replaceModels(id, request.models());
        routeCacheService.refresh();
        return toView(requiredChannel(id));
    }

    @PatchMapping("/{id}/status")
    public Map<String, Object> changeStatus(@PathVariable Long id, @Valid @RequestBody StatusRequest request) {
        requiredChannel(id);
        channelMapper.update(null, new LambdaUpdateWrapper<Channel>()
                .eq(Channel::getId, id).set(Channel::getStatus, request.status()));
        routeCacheService.refresh();
        return Map.of("id", id, "status", request.status());
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        requiredChannel(id);
        channelMapper.deleteById(id);
        channelModelMapper.deleteBindingsByChannelId(id);
        routeCacheService.refresh();
    }

    private Channel requiredChannel(Long id) {
        Channel channel = channelMapper.selectById(id);
        if (channel == null) {
            throw BizException.badRequest("channel_not_found", "Channel not found: " + id);
        }
        return channel;
    }

    private Channel toEntity(ChannelUpsertRequest request, Channel existing) {
        Channel channel = new Channel();
        channel.setName(request.name());
        channel.setProvider(defaultValue(request.provider(), "custom"));
        String primaryProtocol;
        try {
            primaryProtocol = ChannelProtocol.parse(request.protocol()).value();
        } catch (IllegalArgumentException e) {
            throw BizException.badRequest("unsupported_protocol", e.getMessage());
        }
        List<String> supportedProtocols = normalizeSupportedProtocols(request.supportedProtocols(), primaryProtocol);
        channel.setProtocol(primaryProtocol);
        try {
            channel.setSupportedProtocols(objectMapper.writeValueAsString(supportedProtocols));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize supported protocols", e);
        }
        channel.setProtocolConfig(request.protocolConfig());
        channel.setBaseUrl(request.baseUrl());
        // 空 API Key 表示编辑时保留当前密钥；创建时必须有值。
        if (StringUtils.hasText(request.apiKey())) {
            channel.setApiKey(request.apiKey());
        } else if (existing != null) {
            channel.setApiKey(existing.getApiKey());
        } else {
            throw BizException.badRequest("missing_api_key", "apiKey is required when creating a channel");
        }
        channel.setPriority(request.priority() == null ? 0 : request.priority());
        channel.setWeight(request.weight() == null || request.weight() <= 0 ? 1 : request.weight());
        channel.setMaxConcurrent(request.maxConcurrent() == null ? 0 : Math.max(0, request.maxConcurrent()));
        channel.setDefaultTestModel(request.defaultTestModel());
        channel.setStatus(request.status() == null ? 1 : request.status());
        channel.setRemark(request.remark());
        return channel;
    }

    private List<String> normalizeSupportedProtocols(List<String> requested, String primaryProtocol) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (requested != null) {
            for (String value : requested) {
                if (!StringUtils.hasText(value)) continue;
                try {
                    normalized.add(ChannelProtocol.parse(value).value());
                } catch (IllegalArgumentException e) {
                    throw BizException.badRequest("unsupported_protocol", e.getMessage());
                }
            }
        }
        if (normalized.isEmpty()) normalized.add(primaryProtocol);
        if (!normalized.contains(primaryProtocol)) {
            throw BizException.badRequest("primary_protocol_not_selected", "The primary protocol must be included in supportedProtocols");
        }
        return List.copyOf(normalized);
    }

    private List<String> supportedProtocols(Channel channel) {
        if (!StringUtils.hasText(channel.getSupportedProtocols())) return List.of(channel.getProtocol());
        try {
            List<String> values = objectMapper.readValue(channel.getSupportedProtocols(), new TypeReference<>() { });
            return normalizeSupportedProtocols(values, channel.getProtocol());
        } catch (Exception e) {
            return List.of(channel.getProtocol());
        }
    }

    private void replaceModels(Long channelId, List<ModelBindingRequest> models) {
        channelModelMapper.deleteBindingsByChannelId(channelId);
        if (models == null) {
            return;
        }
        models.stream().filter(model -> StringUtils.hasText(model.modelName()))
                .sorted(Comparator.comparing(ModelBindingRequest::modelName))
                .forEach(model -> {
                    ChannelModel binding = new ChannelModel();
                    binding.setChannelId(channelId);
                    binding.setModelName(model.modelName().trim());
                    binding.setUpstreamModelName(StringUtils.hasText(model.upstreamModelName()) ? model.upstreamModelName().trim() : null);
                    binding.setStatus(model.status() == null ? 1 : model.status());
                    binding.setRemark(model.remark());
                    channelModelMapper.insert(binding);
                });
    }

    private ChannelView toView(Channel channel) {
        List<ModelBindingView> models = channelModelMapper.selectList(new LambdaQueryWrapper<ChannelModel>()
                        .eq(ChannelModel::getChannelId, channel.getId()).orderByAsc(ChannelModel::getModelName))
                .stream().map(model -> new ModelBindingView(model.getId(), model.getModelName(), model.getUpstreamModelName(),
                        model.getStatus(), model.getRemark())).toList();
        return new ChannelView(channel.getId(), channel.getName(), channel.getProvider(), channel.getProtocol(),
                supportedProtocols(channel), channel.getProtocolConfig(), channel.getBaseUrl(), mask(channel.getApiKey()), channel.getPriority(), channel.getWeight(),
                channel.getMaxConcurrent(), channel.getFailureCount(), channel.getDefaultTestModel(), channel.getStatus(),
                channel.getRemark(), models, channel.getCreateTime(), channel.getUpdateTime());
    }

    private String mask(String apiKey) {
        if (!StringUtils.hasText(apiKey)) return null;
        return apiKey.length() <= 8 ? "********" : apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }

    private String defaultValue(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    public record ModelDiscoveryRequest(@NotBlank String baseUrl, @NotBlank String apiKey, String protocol,
                                        String protocolConfig) { }
    public record ChannelTestRequest(List<String> modelNames, String prompt) { }
    public record ChannelUpsertRequest(@NotBlank String name, String provider, String protocol, List<String> supportedProtocols,
                                       String protocolConfig, @NotBlank String baseUrl, String apiKey, Integer priority, Integer weight,
                                       Integer maxConcurrent, String defaultTestModel, Integer status, String remark,
                                       List<ModelBindingRequest> models) { }
    public record ModelBindingRequest(@NotBlank String modelName, String upstreamModelName, Integer status, String remark) { }
    public record StatusRequest(@NotNull Integer status) { }
    public record ModelBindingView(Long id, String modelName, String upstreamModelName, Integer status, String remark) { }
    public record ChannelView(Long id, String name, String provider, String protocol, List<String> supportedProtocols,
                               String protocolConfig, String baseUrl,
                              String apiKeyMasked, Integer priority, Integer weight, Integer maxConcurrent, Integer failureCount,
                              String defaultTestModel, Integer status, String remark, List<ModelBindingView> models,
                              Object createTime, Object updateTime) { }
}
