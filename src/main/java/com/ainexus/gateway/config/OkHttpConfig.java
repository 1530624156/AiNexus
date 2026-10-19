package com.ainexus.gateway.config;

import lombok.extern.slf4j.Slf4j;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * OkHttp 转发客户端配置(双实例, 共享同一连接池):
 * <ul>
 *   <li>{@code relayClient}       : 普通(非流式)转发, 受 readTimeout 约束, 防止上游挂死占用连接</li>
 *   <li>{@code streamRelayClient} : SSE 流式转发, callTimeout/readTimeout = 0 不设上限(以配置为准),
 *                                   由上游/客户端断开自然结束 —— 长连接 LLM 流式响应必须如此</li>
 * </ul>
 *
 * <p>连接池配置考量: 网关是典型的"多请求对多上游"代理, 需要足够大的空闲连接数与
 * keep-alive 时长以复用 TCP 连接(LLM 厂商普遍短时并发高, 连接复用直接决定 P99 延迟)。
 */
@Slf4j
@Configuration
public class OkHttpConfig {

    @Bean
    public OkHttpClient relayClient(GatewayProperties properties) {
        return buildClient(properties, false);
    }

    @Bean
    public OkHttpClient streamRelayClient(GatewayProperties properties) {
        return buildClient(properties, true);
    }

    private OkHttpClient buildClient(GatewayProperties properties, boolean stream) {
        GatewayProperties.HttpClient http = properties.getHttpClient();

        ConnectionPool pool = new ConnectionPool(
                http.getPoolMaxIdle(), http.getPoolKeepAliveSeconds(), TimeUnit.SECONDS);
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(512);
        dispatcher.setMaxRequestsPerHost(128);

        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectionPool(pool)
                .dispatcher(dispatcher)
                .connectTimeout(http.getConnectTimeoutMs(), TimeUnit.MILLISECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true);

        if (stream) {
            long callTimeout = http.getStreamCallTimeoutMs();
            long readTimeout = http.getStreamReadTimeoutMs();
            builder.callTimeout(callTimeout > 0 ? callTimeout : 0, TimeUnit.MILLISECONDS);
            builder.readTimeout(readTimeout > 0 ? readTimeout : 0, TimeUnit.MILLISECONDS);
        } else {
            builder.readTimeout(http.getReadTimeoutMs(), TimeUnit.MILLISECONDS);
        }

        if (http.isLogRequest()) {
            HttpLoggingInterceptor logging = new HttpLoggingInterceptor(msg -> log.info("[relay] {}", msg));
            logging.redactHeader("Authorization"); // 厂商 Key 一律脱敏, 绝不落日志
            logging.setLevel(HttpLoggingInterceptor.Level.BASIC);
            builder.addInterceptor(logging);
        }
        return builder.build();
    }
}