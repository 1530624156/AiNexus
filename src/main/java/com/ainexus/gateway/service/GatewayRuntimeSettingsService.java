package com.ainexus.gateway.service;

import com.ainexus.gateway.common.BizException;
import com.ainexus.gateway.entity.GatewayRuntimeSetting;
import com.ainexus.gateway.mapper.GatewayRuntimeSettingMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;

/** Holds the latest persisted gateway controls as an immutable in-memory snapshot. */
@Service
@RequiredArgsConstructor
public class GatewayRuntimeSettingsService {
    private final GatewayRuntimeSettingMapper mapper;
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
        row.setId(1L); row.setAccessUrl(next.accessUrl()); row.setProtocolConversionEnabled(next.protocolConversionEnabled() ? 1 : 0);
        row.setMaxConcurrent(next.maxConcurrent()); row.setQueueCapacity(next.queueCapacity());
        row.setNonStreamTimeoutSeconds(next.nonStreamTimeoutSeconds()); row.setStreamIdleTimeoutSeconds(next.streamIdleTimeoutSeconds()); row.setMaxPayloadMb(next.maxPayloadMb());
        row.setGithubLoginOnly(next.githubLoginOnly() ? 1 : 0);
        mapper.updateById(row);
        current = next;
        return next;
    }

    private Settings validate(Update request) {
        String baseUrl = request.accessUrl() == null ? "" : request.accessUrl().trim().replaceAll("/+$", "");
        String accessUrl = baseUrl.endsWith("/v1") ? baseUrl : baseUrl + "/v1";
        try {
            URI uri = URI.create(accessUrl);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) || !uri.getPath().endsWith("/v1")) throw new IllegalArgumentException();
        } catch (Exception e) { throw BizException.badRequest("invalid_access_url", "Access URL must use HTTP or HTTPS and end with /v1"); }
        int maxConcurrent = nonNegative(request.maxConcurrent(), "maxConcurrent");
        int queueCapacity = nonNegative(request.queueCapacity(), "queueCapacity");
        int nonStreamTimeout = positive(request.nonStreamTimeoutSeconds(), "nonStreamTimeoutSeconds");
        int streamIdleTimeout = nonNegative(request.streamIdleTimeoutSeconds(), "streamIdleTimeoutSeconds");
        int payload = positive(request.maxPayloadMb(), "maxPayloadMb");
        if (payload > 1024) throw BizException.badRequest("invalid_max_payload", "maxPayloadMb must be at most 1024");
        return new Settings(accessUrl, Boolean.TRUE.equals(request.protocolConversionEnabled()), maxConcurrent, queueCapacity, nonStreamTimeout, streamIdleTimeout, payload, Boolean.TRUE.equals(request.githubLoginOnly()));
    }
    private int nonNegative(Integer value, String name) { int v = value == null ? 0 : value; if (v < 0) throw BizException.badRequest("invalid_setting", name + " must not be negative"); return v; }
    private int positive(Integer value, String name) { int v = value == null ? 0 : value; if (v <= 0) throw BizException.badRequest("invalid_setting", name + " must be positive"); return v; }
    private Settings from(GatewayRuntimeSetting row) { return new Settings(row.getAccessUrl(), row.getProtocolConversionEnabled() != null && row.getProtocolConversionEnabled() == 1, row.getMaxConcurrent(), row.getQueueCapacity(), row.getNonStreamTimeoutSeconds(), row.getStreamIdleTimeoutSeconds(), row.getMaxPayloadMb(), row.getGithubLoginOnly() != null && row.getGithubLoginOnly() == 1); }

    public record Update(String accessUrl, Boolean protocolConversionEnabled, Integer maxConcurrent, Integer queueCapacity, Integer nonStreamTimeoutSeconds, Integer streamIdleTimeoutSeconds, Integer maxPayloadMb, Boolean githubLoginOnly) { }
    public record Settings(String accessUrl, boolean protocolConversionEnabled, int maxConcurrent, int queueCapacity, int nonStreamTimeoutSeconds, int streamIdleTimeoutSeconds, int maxPayloadMb, boolean githubLoginOnly) {
        static Settings defaults() { return new Settings("http://localhost:8080/v1", true, 0, 0, 180, 0, 16, false); }
    }
}
