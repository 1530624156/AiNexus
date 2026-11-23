package com.ainexus.gateway.service;

import com.ainexus.gateway.common.BizException;
import com.ainexus.gateway.entity.GatewayRuntimeSetting;
import com.ainexus.gateway.mapper.GatewayRuntimeSettingMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Holds the latest persisted gateway controls as an immutable in-memory snapshot. */
@Service
@RequiredArgsConstructor
public class GatewayRuntimeSettingsService {
    private static final String DEFAULT_ACCESS_URL_NAME = "Default";
    private static final int MAX_ACCESS_URLS = 50;
    private static final int MAX_ACCESS_URL_LENGTH = 512;
    private final GatewayRuntimeSettingMapper mapper;
    private final ObjectMapper objectMapper;
    private volatile Settings current = Settings.defaults();

    @PostConstruct
    public void load() {
        GatewayRuntimeSetting row = mapper.selectById(1L);
        if (row != null) current = from(row);
    }

    public Settings get() { return current; }

    public synchronized Settings update(Update request) {
        Settings next = validate(request);
        GatewayRuntimeSetting row = new GatewayRuntimeSetting();
        row.setId(1L); row.setAccessUrl(next.accessUrl()); row.setAccessUrls(writeAccessUrls(next.accessUrls()));
        row.setProtocolConversionEnabled(next.protocolConversionEnabled() ? 1 : 0);
        row.setMaxConcurrent(next.maxConcurrent()); row.setQueueCapacity(next.queueCapacity()); row.setQueueTimeoutSeconds(next.queueTimeoutSeconds());
        row.setNonStreamTimeoutSeconds(next.nonStreamTimeoutSeconds()); row.setStreamIdleTimeoutSeconds(next.streamIdleTimeoutSeconds()); row.setMaxPayloadMb(next.maxPayloadMb());
        row.setGithubLoginOnly(next.githubLoginOnly() ? 1 : 0);
        row.setCrazyModeEnabled(next.crazyModeEnabled() ? 1 : 0);
        mapper.updateById(row);
        current = next;
        return next;
    }

    private Settings validate(Update request) {
        List<AccessUrl> accessUrls = normalizeAccessUrls(request.accessUrls(), request.accessUrl());
        int maxConcurrent = nonNegative(request.maxConcurrent(), "maxConcurrent");
        int queueCapacity = nonNegative(request.queueCapacity(), "queueCapacity");
        int queueTimeout = positive(request.queueTimeoutSeconds(), "queueTimeoutSeconds");
        int nonStreamTimeout = positive(request.nonStreamTimeoutSeconds(), "nonStreamTimeoutSeconds");
        int streamIdleTimeout = nonNegative(request.streamIdleTimeoutSeconds(), "streamIdleTimeoutSeconds");
        int payload = positive(request.maxPayloadMb(), "maxPayloadMb");
        if (payload > 1024) throw BizException.badRequest("invalid_max_payload", "maxPayloadMb must be at most 1024");
        return new Settings(accessUrls.getFirst().url(), accessUrls, Boolean.TRUE.equals(request.protocolConversionEnabled()), maxConcurrent, queueCapacity, queueTimeout, nonStreamTimeout, streamIdleTimeout, payload, Boolean.TRUE.equals(request.githubLoginOnly()), Boolean.TRUE.equals(request.crazyModeEnabled()));
    }

    private List<AccessUrl> normalizeAccessUrls(List<AccessUrl> requestedAccessUrls, String legacyAccessUrl) {
        List<AccessUrl> supplied = requestedAccessUrls == null
                ? List.of(new AccessUrl(DEFAULT_ACCESS_URL_NAME, legacyAccessUrl))
                : requestedAccessUrls;
        if (supplied.isEmpty()) {
            throw BizException.badRequest("invalid_access_urls", "At least one access URL is required");
        }
        if (supplied.size() > MAX_ACCESS_URLS) {
            throw BizException.badRequest("invalid_access_urls", "At most " + MAX_ACCESS_URLS + " access URLs are allowed");
        }
        List<AccessUrl> normalized = new ArrayList<>(supplied.size());
        Set<String> names = new HashSet<>();
        Set<String> urls = new HashSet<>();
        for (AccessUrl accessUrl : supplied) {
            String name = accessUrl == null || accessUrl.name() == null ? "" : accessUrl.name().trim();
            if (!StringUtils.hasText(name) || name.length() > 128) {
                throw BizException.badRequest("invalid_access_url_name", "Access URL name must be between 1 and 128 characters");
            }
            String url = normalizeAccessUrl(accessUrl == null ? null : accessUrl.url());
            if (url.length() > MAX_ACCESS_URL_LENGTH) {
                throw BizException.badRequest("invalid_access_url", "Access URL must not exceed " + MAX_ACCESS_URL_LENGTH + " characters");
            }
            if (!names.add(name)) throw BizException.badRequest("duplicate_access_url_name", "Access URL names must be unique");
            if (!urls.add(url)) throw BizException.badRequest("duplicate_access_url", "Access URLs must be unique");
            normalized.add(new AccessUrl(name, url));
        }
        return List.copyOf(normalized);
    }

    private String normalizeAccessUrl(String value) {
        String baseUrl = value == null ? "" : value.trim().replaceAll("/+$", "");
        String accessUrl = baseUrl.endsWith("/v1") ? baseUrl : baseUrl + "/v1";
        try {
            URI uri = URI.create(accessUrl);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || !StringUtils.hasText(uri.getHost()) || !uri.getPath().endsWith("/v1")) {
                throw new IllegalArgumentException();
            }
            return accessUrl;
        } catch (Exception e) {
            throw BizException.badRequest("invalid_access_url", "Access URL must use HTTP or HTTPS and end with /v1");
        }
    }

    private int nonNegative(Integer value, String name) { int v = value == null ? 0 : value; if (v < 0) throw BizException.badRequest("invalid_setting", name + " must not be negative"); return v; }
    private int positive(Integer value, String name) { int v = value == null ? 0 : value; if (v <= 0) throw BizException.badRequest("invalid_setting", name + " must be positive"); return v; }
    private int defaultIfNull(Integer value, int fallback) { return value == null ? fallback : value; }

    private Settings from(GatewayRuntimeSetting row) {
        List<AccessUrl> accessUrls = readAccessUrls(row.getAccessUrls(), row.getAccessUrl());
        return new Settings(accessUrls.getFirst().url(), accessUrls, row.getProtocolConversionEnabled() != null && row.getProtocolConversionEnabled() == 1,
                row.getMaxConcurrent(), row.getQueueCapacity(), defaultIfNull(row.getQueueTimeoutSeconds(), Settings.defaults().queueTimeoutSeconds()),
                row.getNonStreamTimeoutSeconds(), row.getStreamIdleTimeoutSeconds(), row.getMaxPayloadMb(), row.getGithubLoginOnly() != null && row.getGithubLoginOnly() == 1,
                row.getCrazyModeEnabled() != null && row.getCrazyModeEnabled() == 1);
    }

    private List<AccessUrl> readAccessUrls(String json, String legacyAccessUrl) {
        if (StringUtils.hasText(json)) {
            try {
                List<AccessUrl> parsed = objectMapper.readValue(json, new TypeReference<>() { });
                if (parsed != null && !parsed.isEmpty()) return normalizeAccessUrls(parsed, legacyAccessUrl);
            } catch (Exception ignored) {
                // Preserve availability for legacy or manually edited rows by falling back to access_url.
            }
        }
        return List.of(new AccessUrl(DEFAULT_ACCESS_URL_NAME, normalizeAccessUrl(legacyAccessUrl)));
    }

    private String writeAccessUrls(List<AccessUrl> accessUrls) {
        try {
            return objectMapper.writeValueAsString(accessUrls);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize access URLs", e);
        }
    }

    public record Update(String accessUrl, List<AccessUrl> accessUrls, Boolean protocolConversionEnabled, Integer maxConcurrent, Integer queueCapacity, Integer queueTimeoutSeconds, Integer nonStreamTimeoutSeconds, Integer streamIdleTimeoutSeconds, Integer maxPayloadMb, Boolean githubLoginOnly, Boolean crazyModeEnabled) { }
    public record AccessUrl(String name, String url) { }
    public record Settings(String accessUrl, List<AccessUrl> accessUrls, boolean protocolConversionEnabled, int maxConcurrent, int queueCapacity, int queueTimeoutSeconds, int nonStreamTimeoutSeconds, int streamIdleTimeoutSeconds, int maxPayloadMb, boolean githubLoginOnly, boolean crazyModeEnabled) {
        static Settings defaults() {
            List<AccessUrl> accessUrls = List.of(new AccessUrl(DEFAULT_ACCESS_URL_NAME, "http://localhost:8080/v1"));
            return new Settings(accessUrls.getFirst().url(), accessUrls, true, 0, 0, 60, 180, 0, 16, false, false);
        }
    }
}
