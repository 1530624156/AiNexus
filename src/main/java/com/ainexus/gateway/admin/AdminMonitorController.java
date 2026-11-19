package com.ainexus.gateway.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Real-time operational snapshot for the administrator monitoring screen. */
@RestController
@RequestMapping("/api/admin/monitor")
@RequiredArgsConstructor
public class AdminMonitorController {
    private final AdminMonitorSnapshotService snapshotService;

    /** Retained for first-load diagnostics; the monitoring screen receives subsequent updates over WebSocket. */
    @GetMapping("/snapshot")
    public Snapshot snapshot() { return snapshotService.snapshot(); }

    public record Snapshot(int online, int streamingRequests, int concurrent, int waiting, int maxConcurrent, int queueCapacity,
                           double cpu, double processCpu, int cpuCores, double memory, double memoryUsedGb, double memoryTotalGb,
                           long uptimeSeconds, List<User> users, List<Log> logs, List<HistoryPoint> history) { }
    public record User(String name, String ip, String region, String status) { }
    public record Log(String time, String level, String message) { }
    public record HistoryPoint(long time, int concurrent, int waiting, int activeUsers, int streams) { }
}
