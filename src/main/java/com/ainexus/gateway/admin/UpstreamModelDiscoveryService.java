package com.ainexus.gateway.admin;

import com.ainexus.gateway.common.BizException;
import com.ainexus.gateway.route.ChannelProtocol;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 管理端的上游模型目录发现服务。
 *
 * <p>探测只读取管理员当前表单输入，不会保存 API Key。为避免 Admin API 成为 SSRF 探针，拒绝 loopback、
 * site-local、link-local 及任意解析为私有地址的目标；本地 mock 渠道请手工填写模型绑定。
 */
@Service
@RequiredArgsConstructor
public class UpstreamModelDiscoveryService {

    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(12);

    @Qualifier("relayClient")
    private final OkHttpClient relayClient;
    private final ObjectMapper objectMapper;

    public List<DiscoveredModel> discover(String baseUrl, String apiKey, String protocol, String protocolConfig) {
        if (!StringUtils.hasText(baseUrl) || !StringUtils.hasText(apiKey)) {
            throw BizException.badRequest("missing_discovery_config", "baseUrl and apiKey are required to fetch upstream models");
        }
        ChannelProtocol parsedProtocol;
        try {
            parsedProtocol = ChannelProtocol.parse(protocol);
        } catch (IllegalArgumentException e) {
            throw BizException.badRequest("unsupported_protocol", e.getMessage());
        }
        URI uri = validateBaseUrl(baseUrl);
        String path = modelListPath(protocolConfig);
        String target = stripTrailingSlash(uri.toString()) + path;

        Request.Builder request = new Request.Builder().url(target).get().header("Accept", "application/json");
        if (parsedProtocol == ChannelProtocol.ANTHROPIC_MESSAGES) {
            request.header("x-api-key", apiKey).header("anthropic-version", "2023-06-01");
        } else {
            request.header("Authorization", "Bearer " + apiKey);
        }

        try (Response response = relayClient.newBuilder()
                .callTimeout(DISCOVERY_TIMEOUT)
                .readTimeout(DISCOVERY_TIMEOUT)
                .build()
                .newCall(request.build())
                .execute()) {
            ResponseBody body = response.body();
            String payload = body == null ? "" : body.string();
            if (!response.isSuccessful()) {
                throw BizException.badRequest("upstream_model_discovery_failed",
                        "Upstream returned HTTP " + response.code() + " while fetching /v1/models");
            }
            return parseModels(payload);
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            throw BizException.badRequest("upstream_model_discovery_failed",
                    "Unable to fetch upstream models: " + e.getMessage());
        }
    }

    private List<DiscoveredModel> parseModels(String payload) throws IOException {
        JsonNode root = objectMapper.readTree(payload);
        JsonNode data = root.path("data");
        if (!data.isArray()) {
            throw BizException.badRequest("invalid_upstream_model_list", "Upstream response does not contain a models data array");
        }
        Map<String, String> names = new java.util.LinkedHashMap<>();
        for (JsonNode item : data) {
            String id = item.path("id").asText();
            if (!StringUtils.hasText(id)) {
                continue;
            }
            String owner = item.path("owned_by").asText(item.path("ownedBy").asText(""));
            names.putIfAbsent(id, owner);
        }
        return names.entrySet().stream()
                .map(entry -> new DiscoveredModel(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(DiscoveredModel::id, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private URI validateBaseUrl(String raw) {
        try {
            URI uri = URI.create(raw.trim());
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                throw BizException.badRequest("invalid_discovery_url", "baseUrl must use http or https");
            }
            if (!StringUtils.hasText(uri.getHost())) {
                throw BizException.badRequest("invalid_discovery_url", "baseUrl host is required");
            }
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (isPrivateOrLocal(address)) {
                    throw BizException.badRequest("discovery_url_not_allowed", "Private or local addresses are not allowed for model discovery");
                }
            }
            return uri;
        } catch (IllegalArgumentException | UnknownHostException e) {
            throw BizException.badRequest("invalid_discovery_url", "Invalid or unreachable baseUrl");
        }
    }

    private boolean isPrivateOrLocal(InetAddress address) {
        return address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress();
    }

    private String modelListPath(String protocolConfig) {
        if (StringUtils.hasText(protocolConfig)) {
            try {
                String override = objectMapper.readTree(protocolConfig).path("modelListPath").asText();
                if (StringUtils.hasText(override) && override.startsWith("/")) {
                    return override;
                }
            } catch (Exception ignored) {
                // protocol_config 的主校验仍由渠道保存/路由阶段完成；模型探测忽略非法附加字段。
            }
        }
        return "/v1/models";
    }

    private String stripTrailingSlash(String value) {
        return value.replaceAll("/+$", "");
    }

    public record DiscoveredModel(String id, String ownedBy) { }
}
