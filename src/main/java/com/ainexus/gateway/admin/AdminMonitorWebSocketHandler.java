package com.ainexus.gateway.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Admin-only monitor stream. One shared sampler produces a snapshot once per second
 * and broadcasts the serialized payload to all dashboard sessions.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AdminMonitorWebSocketHandler extends TextWebSocketHandler {
    private final AdminMonitorSnapshotService snapshotService;
    private final ObjectMapper objectMapper;
    private final MonitorTaskScheduler scheduler;
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private volatile String latestSnapshot;
    private ScheduledFuture<?> broadcastTask;

    @PostConstruct
    void startBroadcast() {
        broadcastSnapshot();
        broadcastTask = scheduler.scheduleAtFixedRate(this::broadcastSnapshot, Duration.ofSeconds(1));
    }

    @PreDestroy
    void stopBroadcast() {
        if (broadcastTask != null) broadcastTask.cancel(true);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), session);
        log.info("[monitor-ws] connected: {}, sessions={}", session.getId(), sessions.size());
        String snapshot = latestSnapshot;
        if (snapshot != null) send(session, snapshot);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        log.info("[monitor-ws] closed: {}, {}, sessions={}", session.getId(), status, sessions.size());
    }

    private void broadcastSnapshot() {
        try {
            String snapshot = objectMapper.writeValueAsString(snapshotService.snapshot());
            latestSnapshot = snapshot;
            sessions.values().forEach(session -> send(session, snapshot));
        } catch (Exception exception) {
            log.warn("[monitor-ws] snapshot generation failed", exception);
        }
    }

    private void send(WebSocketSession session, String snapshot) {
        if (!session.isOpen()) {
            sessions.remove(session.getId());
            return;
        }
        try {
            synchronized (session) {
                if (session.isOpen()) session.sendMessage(new TextMessage(snapshot));
            }
        } catch (Exception exception) {
            sessions.remove(session.getId());
            log.warn("[monitor-ws] snapshot send failed for {}", session.getId(), exception);
            try { session.close(CloseStatus.SERVER_ERROR); } catch (Exception closeError) { log.debug("[monitor-ws] session close failed", closeError); }
        }
    }

    @Component
    public static class MonitorTaskScheduler extends ThreadPoolTaskScheduler {
        public MonitorTaskScheduler() {
            setPoolSize(1);
            setThreadNamePrefix("monitor-ws-");
            setWaitForTasksToCompleteOnShutdown(true);
            setAwaitTerminationSeconds(5);
        }
    }
}
