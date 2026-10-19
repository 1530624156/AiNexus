package com.ainexus.gateway.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.List;

/** In-memory one-minute concurrency samples for the live dashboard. */
@Service
@RequiredArgsConstructor
public class ConcurrencyHistoryService {
    private final GatewayConcurrencyService gatewayConcurrencyService;
    private final GatewayActivityService activityService;
    private final ArrayDeque<Point> history = new ArrayDeque<>();

    @PostConstruct
    public void initialSample() { sample(); }

    @Scheduled(initialDelay = 10_000, fixedDelay = 60_000)
    public synchronized void sample() {
        GatewayConcurrencyService.Snapshot snapshot = gatewayConcurrencyService.snapshot();
        history.addLast(new Point(LocalDateTime.now(), snapshot.active(), snapshot.waiting(), activityService.activeUsers(), activityService.streamingRequests()));
        LocalDateTime threshold = LocalDateTime.now().minusDays(31);
        while (!history.isEmpty() && history.getFirst().time().isBefore(threshold)) history.removeFirst();
    }
    public synchronized List<Point> since(LocalDateTime start) {
        sampleCurrentIfStale();
        return history.stream().filter(point -> !point.time().isBefore(start)).toList();
    }

    private void sampleCurrentIfStale() {
        if (history.isEmpty() || history.getLast().time().isBefore(LocalDateTime.now().minusSeconds(5))) sample();
    }
    public record Point(LocalDateTime time, int concurrent, int waiting, int activeUsers, int streams) { }
}
