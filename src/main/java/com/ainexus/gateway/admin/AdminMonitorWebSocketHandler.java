package com.ainexus.gateway.admin;

import com.ainexus.gateway.auth.ConsoleSessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/** Admin-only monitor stream. Each connection receives an authenticated live snapshot every second. */
@Component
@Slf4j
@RequiredArgsConstructor
public class AdminMonitorWebSocketHandler extends TextWebSocketHandler {
    private final AdminMonitorSnapshotService snapshotService;
    private final ObjectMapper objectMapper;
    private final MonitorTaskScheduler scheduler;
    private final Map<String, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("[monitor-ws] connected: {}", session.getId());
        tasks.put(session.getId(), scheduler.scheduleAtFixedRate(() -> sendSnapshot(session), 1000));
        sendSnapshot(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        ScheduledFuture<?> task = tasks.remove(session.getId());
        if (task != null) task.cancel(true);
        log.info("[monitor-ws] closed: {}, {}", session.getId(), status);
    }

    private void sendSnapshot(WebSocketSession session) {
        if (!session.isOpen()) return;
        try {
            synchronized (session) {
                if (session.isOpen()) session.sendMessage(new TextMessage(objectMapper.writeValueAsString(snapshotService.snapshot())));
            }
        } catch (Exception exception) {
            log.warn("[monitor-ws] snapshot send failed for {}", session.getId(), exception);
            try { session.close(CloseStatus.SERVER_ERROR); } catch (Exception closeError) { log.debug("[monitor-ws] session close failed", closeError); }
        }
    }

    @Component
    public static class MonitorTaskScheduler extends ThreadPoolTaskScheduler {
        public MonitorTaskScheduler() {
            setPoolSize(2);
            setThreadNamePrefix("monitor-ws-");
            initialize();
        }
    }
}
