package com.ainexus.gateway.config;

import com.ainexus.gateway.admin.AdminMonitorWebSocketHandler;
import com.ainexus.gateway.auth.ConsoleSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** WebSocket registration and administrator session verification for monitor delivery. */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class MonitorWebSocketConfig implements WebSocketConfigurer {
    private final AdminMonitorWebSocketHandler handler;
    private final ConsoleSessionService sessionService;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/admin/monitor/ws")
                .addInterceptors(new AdminMonitorHandshakeInterceptor(sessionService))
                .setAllowedOriginPatterns("*");
    }

    private record AdminMonitorHandshakeInterceptor(ConsoleSessionService sessionService) implements HandshakeInterceptor {
        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Map<String, Object> attributes) {
            String token = request.getHeaders().getFirst("Sec-WebSocket-Protocol");
            if (token == null || token.isBlank()) token = queryParameter(request.getURI(), "token");
            ConsoleSessionService.SessionAccount account = token == null ? null : sessionService.verify(token);
            if (account == null || !"ADMIN".equals(account.role())) {
                response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
                return false;
            }
            attributes.put("accountId", account.id());
            return true;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Exception exception) { }

        private String queryParameter(URI uri, String name) {
            String query = uri.getRawQuery();
            if (query == null) return null;
            for (String pair : query.split("&")) {
                int separator = pair.indexOf('=');
                if (separator > 0 && name.equals(pair.substring(0, separator))) return URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
            }
            return null;
        }
    }
}
